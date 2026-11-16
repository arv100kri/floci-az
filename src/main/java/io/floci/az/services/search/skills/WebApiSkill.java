package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code #Microsoft.Skills.Custom.WebApiSkill}: sends the records to {@code uri} with {@code httpMethod} (POST by
 * default) and {@code httpHeaders}, at most {@code batchSize} records (1000 by default) per call and at most
 * {@code degreeOfParallelism} calls (5 by default) at a time, each within {@code timeout} (30 seconds by default). The
 * request and response follow the documented contract: {@code {"values":[{"recordId","data"}]}} out, and back the same
 * records with {@code data}, {@code errors} and {@code warnings}, matched by {@code recordId}. 429, 502 and 503 are
 * retried twice. A failed call, a response that is not {@code application/json} or has no {@code values}, and a record
 * that is missing or repeated in the response are errors; the endpoint's own errors and warnings are reported as such.
 * Records are batched within one document, as the pipeline enriches documents one at a time.
 */
final class WebApiSkill implements SkillExecutor {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_BATCH_SIZE = 1000;
    static final int DEFAULT_PARALLELISM = 5;

    private static final Set<Integer> RETRIED = Set.of(429, 502, 503);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SkillHttp http;

    WebApiSkill(SkillHttp http) {
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
        URI uri = parseUri(uriText);
        if (uri == null) {
            messages.error(skill, "Could not execute skill because the Web Api request failed. The uri '"
                + WebApiSecrets.redactUri(uriText) + "' is not a valid http or https URI.");
            return results;
        }
        String method = SkillDefinition.text(json, "httpMethod");
        Request request = new Request(skill, uri, method == null ? "POST" : method.toUpperCase(Locale.ROOT),
            headers(json), duration(json, "timeout", DEFAULT_TIMEOUT));
        int batchSize = Math.max(1, intParameter(json, "batchSize", DEFAULT_BATCH_SIZE));
        int parallelism = Math.clamp(intParameter(json, "degreeOfParallelism", DEFAULT_PARALLELISM), 1, 10);
        List<List<Integer>> batches = new ArrayList<>();
        for (int start = 0; start < records.size(); start += batchSize) {
            List<Integer> batch = new ArrayList<>();
            for (int index = start; index < Math.min(records.size(), start + batchSize); index++) {
                batch.add(index);
            }
            batches.add(batch);
        }
        List<BatchResult> outcomes = ParallelCalls.run(batches, parallelism,
            batch -> callBatch(request, batch, records),
            reason -> BatchResult.failed(skill, "Could not execute skill because the Web Api request failed. "
                + reason));
        for (BatchResult outcome : outcomes) {
            messages.addAll(outcome.messages());
            outcome.outputs().forEach(results::set);
        }
        return results;
    }

    /** The URI when it is an absolute http or https URI, else null. */
    static URI parseUri(String text) {
        if (text == null) {
            return null;
        }
        try {
            URI uri = new URI(text.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Map<String, String> headers(ObjectNode json) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        if (json.get("httpHeaders") instanceof ObjectNode custom) {
            Iterator<Map.Entry<String, JsonNode>> fields = custom.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isTextual()) {
                    headers.put(field.getKey(), field.getValue().textValue());
                }
            }
        }
        return headers;
    }

    private BatchResult callBatch(Request request, List<Integer> batch, List<ObjectNode> records) {
        SkillDefinition skill = request.skill();
        SkillMessages messages = new SkillMessages();
        Map<Integer, ObjectNode> outputs = new HashMap<>();
        BatchResult result = new BatchResult(messages, outputs);
        ObjectNode body = NODES.objectNode();
        ArrayNode values = body.putArray("values");
        for (int index : batch) {
            ObjectNode value = values.addObject();
            value.put("recordId", String.valueOf(index));
            value.set("data", records.get(index));
        }
        SkillHttp.Reply reply = send(request, SearchJson.writeBytes(body), messages);
        if (reply == null) {
            return result;
        }
        if (!reply.isSuccess()) {
            messages.error(skill, "Could not execute skill because the Web Api request failed. The Web Api answered "
                + "with status " + reply.status() + ": " + reply.excerpt());
            return result;
        }
        if (!reply.isJson()) {
            messages.error(skill, "Could not execute skill because Web API skill response is invalid. The response "
                + "Content-Type '" + reply.contentType() + "' is not application/json.");
            return result;
        }
        JsonNode parsed = parse(reply.body());
        if (parsed == null || !(parsed.get("values") instanceof ArrayNode responseValues)) {
            messages.error(skill, "Could not execute skill because Web API skill response is invalid. The response "
                + "is not a JSON object with a 'values' array.");
            return result;
        }
        Map<String, List<JsonNode>> byRecord = new HashMap<>();
        for (JsonNode value : responseValues) {
            JsonNode recordId = value.get("recordId");
            if (recordId != null && recordId.isValueNode() && !recordId.isNull()) {
                byRecord.computeIfAbsent(recordId.asText(), key -> new ArrayList<>()).add(value);
            }
        }
        for (int index : batch) {
            String recordId = String.valueOf(index);
            List<JsonNode> matches = byRecord.get(recordId);
            if (matches == null || matches.size() != 1) {
                messages.error(skill, "Could not execute skill because Web API skill response is invalid. The "
                    + "response has " + (matches == null ? "no record" : "more than one record") + " with recordId '"
                    + recordId + "'.");
                continue;
            }
            JsonNode record = matches.get(0);
            reportAll(record.get("errors"), message -> messages.error(skill,
                "Web API skill response contains errors. " + message));
            reportAll(record.get("warnings"), message -> messages.warning(skill,
                "Web API skill response contains warnings. " + message));
            if (record.get("data") instanceof ObjectNode data) {
                outputs.put(index, data);
            }
        }
        return result;
    }

    /** Sends one call; null, with an error reported, when there is no response to read. */
    private SkillHttp.Reply send(Request request, byte[] body, SkillMessages messages) {
        String target = WebApiSecrets.redactUri(request.uri().toString());
        try {
            return http.send(request.method(), request.uri(), request.headers(), body, request.timeout(), RETRIED);
        } catch (HttpTimeoutException e) {
            messages.error(request.skill(), "Skill did not execute within the time limit. The Web Api request to '"
                + target + "' did not answer within " + request.timeout().toSeconds() + " seconds.");
        } catch (IOException e) {
            messages.error(request.skill(), "Could not execute skill because the Web Api request failed. The request "
                + "to '" + target + "' failed: " + describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            messages.error(request.skill(), "Could not execute skill because the Web Api request failed. The request "
                + "was interrupted.");
        }
        return null;
    }

    private static void reportAll(JsonNode list, Consumer<String> report) {
        if (!(list instanceof ArrayNode items)) {
            return;
        }
        for (JsonNode item : items) {
            String message = item.isObject() ? SkillDefinition.text(item, "message") : item.asText();
            report.accept(message == null ? item.toString() : message);
        }
    }

    static JsonNode parse(byte[] body) {
        try {
            return SearchJson.MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    /** A short description of a failure: its message, or its type when it has none. */
    static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    static int intParameter(ObjectNode json, String name, int defaultValue) {
        JsonNode value = json.get(name);
        return value != null && value.isNumber() && value.canConvertToInt() ? value.intValue() : defaultValue;
    }

    /** An ISO 8601 duration parameter such as {@code PT60S}, clamped to the documented 1 to 230 seconds. */
    static Duration duration(ObjectNode json, String name, Duration defaultValue) {
        String text = SkillDefinition.text(json, name);
        if (text == null) {
            return defaultValue;
        }
        try {
            Duration parsed = Duration.parse(text.trim());
            if (parsed.compareTo(Duration.ofSeconds(1)) < 0) {
                return Duration.ofSeconds(1);
            }
            return parsed.compareTo(Duration.ofSeconds(230)) > 0 ? Duration.ofSeconds(230) : parsed;
        } catch (DateTimeParseException e) {
            return defaultValue;
        }
    }

    /**
     * What every call of one skill execution shares.
     *
     * @param skill   the skill
     * @param uri     the endpoint
     * @param method  the HTTP method
     * @param headers the request headers
     * @param timeout the timeout of one attempt
     */
    private record Request(SkillDefinition skill, URI uri, String method, Map<String, String> headers,
                           Duration timeout) {
    }

    /**
     * What one call produced.
     *
     * @param messages its warnings and errors
     * @param outputs  the outputs of the records it enriched, by record index
     */
    private record BatchResult(SkillMessages messages, Map<Integer, ObjectNode> outputs) {

        static BatchResult failed(SkillDefinition skill, String message) {
            SkillMessages messages = new SkillMessages();
            messages.error(skill, message);
            return new BatchResult(messages, Map.of());
        }
    }
}
