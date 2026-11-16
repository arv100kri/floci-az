package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code #Microsoft.Skills.Custom.AmlSkill}: posts each record, as a JSON object of its inputs, to {@code uri} (with
 * {@code key} as a bearer token when set), at most {@code degreeOfParallelism} at a time within {@code timeout}, and
 * takes the outputs from the JSON object the endpoint answers. 429 and 503 are retried twice. A failed call, a response
 * that is not {@code application/json} and invalid JSON are errors, as documented. Token authentication through
 * {@code resourceId} needs a managed identity on Azure Machine Learning and is not emulated.
 */
final class AmlSkill implements SkillExecutor {

    private static final Set<Integer> RETRIED = Set.of(429, 503);

    private final SkillHttp http;

    AmlSkill(SkillHttp http) {
        this.http = http;
    }

    @Override
    public boolean sendsMissingInputsAsNull() {
        return true;
    }

    @Override
    public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
        List<ObjectNode> results = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            results.add(null);
        }
        ObjectNode json = skill.json();
        String uriText = SkillDefinition.text(json, "uri");
        if (uriText == null && SkillDefinition.text(json, "resourceId") != null) {
            messages.error(skill, "Could not execute skill because AML token authentication through 'resourceId' is "
                + "not emulated by floci-az. Set 'uri' and 'key' to call the endpoint.");
            return results;
        }
        URI uri = WebApiSkill.parseUri(uriText);
        if (uri == null) {
            messages.error(skill, "Could not execute skill because the AML request failed. The uri '" + uriText
                + "' is not a valid http or https URI.");
            return results;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        String key = SkillDefinition.text(json, "key");
        if (key != null && !key.isEmpty()) {
            headers.put("Authorization", "Bearer " + key);
        }
        Duration timeout = WebApiSkill.duration(json, "timeout", WebApiSkill.DEFAULT_TIMEOUT);
        int parallelism = Math.clamp(WebApiSkill.intParameter(json, "degreeOfParallelism",
            WebApiSkill.DEFAULT_PARALLELISM), 1, 10);
        List<Integer> indexes = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            indexes.add(index);
        }
        List<Call> calls = ParallelCalls.run(indexes, parallelism,
            index -> call(skill, uri, headers, timeout, records.get(index)),
            reason -> Call.failed(skill, "Could not execute skill because the AML request failed. " + reason));
        for (int index = 0; index < calls.size(); index++) {
            messages.addAll(calls.get(index).messages());
            results.set(index, calls.get(index).output());
        }
        return results;
    }

    private Call call(SkillDefinition skill, URI uri, Map<String, String> headers, Duration timeout,
                      ObjectNode record) {
        SkillMessages messages = new SkillMessages();
        SkillHttp.Reply reply;
        try {
            reply = http.send("POST", uri, headers, SearchJson.writeBytes(record), timeout, RETRIED);
        } catch (HttpTimeoutException e) {
            messages.error(skill, "Skill did not execute within the time limit. The AML request to '" + uri
                + "' did not answer within " + timeout.toSeconds() + " seconds.");
            return new Call(messages, null);
        } catch (IOException e) {
            messages.error(skill, "Could not execute skill because the AML request failed. The request to '" + uri
                + "' failed: " + WebApiSkill.describe(e));
            return new Call(messages, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            messages.error(skill, "Could not execute skill because the AML request failed. The request was "
                + "interrupted.");
            return new Call(messages, null);
        }
        if (!reply.isSuccess()) {
            messages.error(skill, "Could not execute skill because the AML request failed. The endpoint answered with "
                + "status " + reply.status() + ": " + reply.excerpt());
            return new Call(messages, null);
        }
        if (!reply.isJson()) {
            messages.error(skill, "Could not execute skill because the AML response is invalid. The response "
                + "Content-Type '" + reply.contentType() + "' is not application/json.");
            return new Call(messages, null);
        }
        JsonNode parsed = WebApiSkill.parse(reply.body());
        if (!(parsed instanceof ObjectNode output)) {
            messages.error(skill, "Could not execute skill because the AML response is invalid. The response is not "
                + "a JSON object.");
            return new Call(messages, null);
        }
        return new Call(messages, output);
    }

    /**
     * What one call produced.
     *
     * @param messages its warnings and errors
     * @param output   the outputs by name, or null
     */
    private record Call(SkillMessages messages, ObjectNode output) {

        static Call failed(SkillDefinition skill, String message) {
            SkillMessages messages = new SkillMessages();
            messages.error(skill, message);
            return new Call(messages, null);
        }
    }
}
