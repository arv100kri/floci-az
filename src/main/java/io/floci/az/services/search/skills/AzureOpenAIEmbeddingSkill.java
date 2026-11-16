package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
 * {@code #Microsoft.Skills.Text.AzureOpenAIEmbeddingSkill}: turns {@code text} into {@code embedding}. Azure OpenAI is
 * not reachable from the emulator, so the skill calls the OpenAI-compatible model endpoint floci-az is configured with
 * ({@code floci-az.services.search.model-endpoint}) instead of {@code resourceUri}: {@code POST {endpoint}/embeddings}
 * with {@code {"input":[...],"model":...}}, the model being the {@code deploymentId} (or the {@code modelName} when
 * there is none) and {@code dimensions} passed when the skill sets it. Without a model endpoint the document fails as
 * when the resource cannot be reached. As documented, empty text is a warning, text over 8,000 tokens and a missing
 * {@code resourceUri} or {@code deploymentId} are errors; tokens are approximated as for the Split skill.
 */
final class AzureOpenAIEmbeddingSkill implements SkillExecutor {

    static final int MAXIMUM_TOKENS = 8000;

    /** The most inputs one embeddings request carries, the OpenAI API's limit. */
    private static final int INPUTS_PER_REQUEST = 2048;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Set<Integer> RETRIED = Set.of(429, 503);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SkillHttp http;
    private final String modelEndpoint;

    /**
     * @param http          the HTTP client
     * @param modelEndpoint the OpenAI-compatible endpoint, or null when none is configured
     */
    AzureOpenAIEmbeddingSkill(SkillHttp http, String modelEndpoint) {
        this.http = http;
        this.modelEndpoint = modelEndpoint == null || modelEndpoint.isBlank() ? null : modelEndpoint.trim();
    }

    @Override
    public List<RequiredInput> requiredInputs() {
        return List.of(new RequiredInput("text", InputType.STRING));
    }

    @Override
    public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
        List<ObjectNode> results = new ArrayList<>(records.size());
        List<Integer> pending = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            results.add(null);
            String text = records.get(index).get("text").textValue();
            if (text.isBlank()) {
                messages.warning(skill, "Could not execute skill because one or more skill input was invalid. The "
                    + "input 'text' is empty.");
            } else if (approximateTokens(text) > MAXIMUM_TOKENS) {
                messages.error(skill, "Could not execute skill because the input 'text' is larger than "
                    + MAXIMUM_TOKENS + " tokens (about " + approximateTokens(text) + "). Use the Text Split skill to "
                    + "chunk it.");
            } else {
                pending.add(index);
            }
        }
        if (pending.isEmpty()) {
            return results;
        }
        ObjectNode json = skill.json();
        String resourceUri = SkillDefinition.text(json, "resourceUri");
        String deploymentId = SkillDefinition.text(json, "deploymentId");
        if (WebApiSkill.parseUri(resourceUri) == null) {
            messages.error(skill, "Could not execute skill because the skill's 'resourceUri' is null or invalid.");
            return results;
        }
        if (deploymentId == null || deploymentId.isBlank()) {
            messages.error(skill, "Could not execute skill because the skill's 'deploymentId' is null or invalid.");
            return results;
        }
        if (modelEndpoint == null) {
            messages.error(skill, "Could not execute skill because the Web Api request failed. The Azure OpenAI "
                + "resource '" + resourceUri + "' could not be reached: floci-az calls an OpenAI-compatible model "
                + "endpoint instead, and none is configured (floci-az.services.search.model-endpoint).");
            return results;
        }
        URI endpoint = WebApiSkill.parseUri(embeddingsUrl(modelEndpoint));
        if (endpoint == null) {
            messages.error(skill, "Could not execute skill because the Web Api request failed. The model endpoint '"
                + modelEndpoint + "' is not a valid http or https URI.");
            return results;
        }
        for (int start = 0; start < pending.size(); start += INPUTS_PER_REQUEST) {
            List<Integer> chunk = pending.subList(start, Math.min(pending.size(), start + INPUTS_PER_REQUEST));
            embed(skill, endpoint, chunk, records, results, messages);
        }
        return results;
    }

    /** {@code {endpoint}/embeddings}, unless the endpoint already names that path. */
    static String embeddingsUrl(String endpoint) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base.endsWith("/embeddings") ? base : base + "/embeddings";
    }

    /** The approximate token count of {@code text}: one token per {@link SplitSkill#CHARACTERS_PER_TOKEN} characters. */
    static int approximateTokens(String text) {
        return (text.length() + SplitSkill.CHARACTERS_PER_TOKEN - 1) / SplitSkill.CHARACTERS_PER_TOKEN;
    }

    private void embed(SkillDefinition skill, URI endpoint, List<Integer> chunk, List<ObjectNode> records,
                       List<ObjectNode> results, SkillMessages messages) {
        ObjectNode json = skill.json();
        ObjectNode body = NODES.objectNode();
        ArrayNode input = body.putArray("input");
        for (int index : chunk) {
            input.add(records.get(index).get("text").textValue());
        }
        String deploymentId = SkillDefinition.text(json, "deploymentId");
        body.put("model", deploymentId != null ? deploymentId : SkillDefinition.text(json, "modelName"));
        JsonNode dimensions = json.get("dimensions");
        if (dimensions != null && dimensions.isIntegralNumber()) {
            body.put("dimensions", dimensions.intValue());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        String apiKey = SkillDefinition.text(json, "apiKey");
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("api-key", apiKey);
            headers.put("Authorization", "Bearer " + apiKey);
        }
        SkillHttp.Reply reply;
        try {
            reply = http.send("POST", endpoint, headers, SearchJson.writeBytes(body), TIMEOUT, RETRIED);
        } catch (HttpTimeoutException e) {
            messages.error(skill, "Skill did not execute within the time limit. The model endpoint '" + endpoint
                + "' did not answer within " + TIMEOUT.toSeconds() + " seconds.");
            return;
        } catch (IOException e) {
            messages.error(skill, "Could not execute skill because the Web Api request failed. The model endpoint '"
                + endpoint + "' could not be reached: " + WebApiSkill.describe(e));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            messages.error(skill, "Could not execute skill because the Web Api request failed. The request was "
                + "interrupted.");
            return;
        }
        if (!reply.isSuccess()) {
            messages.error(skill, "Could not execute skill because the Web Api request failed. The model endpoint "
                + "answered with status " + reply.status() + ": " + reply.excerpt());
            return;
        }
        List<JsonNode> embeddings = embeddings(WebApiSkill.parse(reply.body()), chunk.size());
        if (embeddings == null) {
            messages.error(skill, "Could not execute skill because Web API skill response is invalid. The model "
                + "endpoint did not answer with one embedding per input in 'data'.");
            return;
        }
        for (int position = 0; position < chunk.size(); position++) {
            ObjectNode output = NODES.objectNode();
            output.set("embedding", embeddings.get(position));
            results.set(chunk.get(position), output);
        }
    }

    /** The embeddings of an OpenAI embeddings response, ordered by their {@code index}; null when malformed. */
    private static List<JsonNode> embeddings(JsonNode response, int expected) {
        if (response == null || !(response.get("data") instanceof ArrayNode data) || data.size() != expected) {
            return null;
        }
        JsonNode[] ordered = new JsonNode[expected];
        for (int position = 0; position < data.size(); position++) {
            JsonNode item = data.get(position);
            JsonNode embedding = item.get("embedding");
            JsonNode indexNode = item.get("index");
            int index = indexNode != null && indexNode.canConvertToInt() ? indexNode.intValue() : position;
            if (!(embedding instanceof ArrayNode) || index < 0 || index >= expected || ordered[index] != null) {
                return null;
            }
            ordered[index] = embedding;
        }
        return List.of(ordered);
    }
}
