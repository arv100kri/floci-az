package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.model.SearchJson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One indexer execution ({@code IndexerExecutionResult}), or the entry a reset leaves in the execution history.
 *
 * @param status               {@code inProgress}, {@code success}, {@code transientFailure},
 *                             {@code persistentFailure} or {@code reset}
 * @param statusDetail         {@code resetDocs} or {@code resync} for those runs, else null (preview API versions)
 * @param mode                 {@code indexingAllDocs}, {@code indexingResetDocs} or {@code indexingResync} (preview)
 * @param errorMessage         the top-level error, or null
 * @param startTime            when the execution started
 * @param endTime              when it ended; null while in progress
 * @param itemsProcessed       items processed, failed ones included
 * @param itemsFailed          items that failed
 * @param errors               item-level errors
 * @param warnings             item-level warnings
 * @param initialTrackingState the change tracking state the execution started from, or null
 * @param finalTrackingState   the change tracking state it finished with, or null
 */
public record ExecutionResult(String status, String statusDetail, String mode, String errorMessage,
                              Instant startTime, Instant endTime, int itemsProcessed, int itemsFailed,
                              List<IndexerIssue> errors, List<IndexerIssue> warnings, String initialTrackingState,
                              String finalTrackingState) {

    public static final String IN_PROGRESS = "inProgress";
    public static final String SUCCESS = "success";
    public static final String TRANSIENT_FAILURE = "transientFailure";
    public static final String PERSISTENT_FAILURE = "persistentFailure";
    public static final String RESET = "reset";

    public static final String MODE_ALL_DOCS = "indexingAllDocs";
    public static final String MODE_RESET_DOCS = "indexingResetDocs";
    public static final String MODE_RESYNC = "indexingResync";

    public ExecutionResult {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    /** The entry a reset adds to the execution history. */
    static ExecutionResult reset(Instant now, String trackingState) {
        return new ExecutionResult(RESET, null, MODE_ALL_DOCS, null, now, now, 0, 0, List.of(), List.of(),
            trackingState, null);
    }

    /** The result of a run that just started. */
    static ExecutionResult started(Instant now, String statusDetail, String mode, String trackingState) {
        return new ExecutionResult(IN_PROGRESS, statusDetail, mode, null, now, null, 0, 0, List.of(), List.of(),
            trackingState, null);
    }

    /** Whether the execution has finished. */
    public boolean finished() {
        return !IN_PROGRESS.equals(status);
    }

    /** The wire form; {@code statusDetail} and {@code mode} only for preview API versions. */
    ObjectNode toJson(boolean preview) {
        ObjectNode json = SearchJson.object();
        json.put("status", status);
        if (preview) {
            putText(json, "statusDetail", statusDetail);
            putText(json, "mode", mode);
        }
        putText(json, "errorMessage", errorMessage);
        putText(json, "startTime", IndexerJson.formatTime(startTime));
        putText(json, "endTime", IndexerJson.formatTime(endTime));
        json.put("itemsProcessed", itemsProcessed);
        json.put("itemsFailed", itemsFailed);
        ArrayNode errorArray = json.putArray("errors");
        errors.forEach(error -> errorArray.add(error.toErrorJson()));
        ArrayNode warningArray = json.putArray("warnings");
        warnings.forEach(warning -> warningArray.add(warning.toWarningJson()));
        putText(json, "initialTrackingState", initialTrackingState);
        putText(json, "finalTrackingState", finalTrackingState);
        return json;
    }

    static ExecutionResult fromJson(JsonNode json) {
        List<IndexerIssue> errors = new ArrayList<>();
        json.path("errors").forEach(error -> errors.add(IndexerIssue.fromErrorJson(error)));
        List<IndexerIssue> warnings = new ArrayList<>();
        json.path("warnings").forEach(warning -> warnings.add(IndexerIssue.fromWarningJson(warning)));
        return new ExecutionResult(SearchJson.text(json, "status"), SearchJson.text(json, "statusDetail"),
            SearchJson.text(json, "mode"), SearchJson.text(json, "errorMessage"),
            time(SearchJson.text(json, "startTime")), time(SearchJson.text(json, "endTime")),
            json.path("itemsProcessed").asInt(), json.path("itemsFailed").asInt(), errors, warnings,
            SearchJson.text(json, "initialTrackingState"), SearchJson.text(json, "finalTrackingState"));
    }

    private static Instant time(String text) {
        return text == null ? null : DocumentCoercer.parseDateTime(text);
    }

    private static void putText(ObjectNode json, String field, String value) {
        if (value == null) {
            json.putNull(field);
        } else {
            json.put(field, value);
        }
    }
}
