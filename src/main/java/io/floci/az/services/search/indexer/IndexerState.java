package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The runtime state of one indexer, as Get Indexer Status reports it and as runs read and advance it: the execution
 * history (newest first, at most {@value #MAX_HISTORY} entries, as Learn documents), the run in progress, the change
 * tracking state, the documents queued by Reset Documents, a pending resync, and whether the indexer is in error.
 * Every method is synchronized; the state is persisted as JSON under {@code dp/indexerstate/{service}/{indexer}}.
 */
public final class IndexerState {

    /** "Execution history contains up to 50 of the most recently completed executions." */
    static final int MAX_HISTORY = 50;

    private final Deque<ExecutionResult> history = new ArrayDeque<>();
    private final Set<String> resetDocumentKeys = new LinkedHashSet<>();
    private final Set<String> resetDatasourceDocumentIds = new LinkedHashSet<>();
    private ExecutionResult current;
    private ChangeTracking tracking = ChangeTracking.NONE;
    private boolean fullOverwrite;
    private boolean error;
    private boolean resyncPending;
    /** Changes whenever a reset invalidates what a run in progress started from. */
    private long epoch;

    /** What a run starts from. */
    record RunStart(ChangeTracking tracking, boolean fullOverwrite, Set<String> resetDocumentKeys,
                    Set<String> resetDatasourceDocumentIds, boolean resync, long epoch) {

        RunStart {
            resetDocumentKeys = Set.copyOf(resetDocumentKeys);
            resetDatasourceDocumentIds = Set.copyOf(resetDatasourceDocumentIds);
        }

        boolean resetDocs() {
            return !resetDocumentKeys.isEmpty() || !resetDatasourceDocumentIds.isEmpty();
        }
    }

    synchronized boolean running() {
        return current != null;
    }

    /** Records a run that just started and returns what it starts from. */
    synchronized RunStart begin(Instant now) {
        boolean resetDocs = !resetDocumentKeys.isEmpty() || !resetDatasourceDocumentIds.isEmpty();
        String detail = resetDocs ? "resetDocs" : resyncPending ? "resync" : null;
        String mode = resetDocs ? ExecutionResult.MODE_RESET_DOCS
            : resyncPending ? ExecutionResult.MODE_RESYNC : ExecutionResult.MODE_ALL_DOCS;
        current = ExecutionResult.started(now, detail, mode, tracking.describe());
        return new RunStart(tracking, fullOverwrite, resetDocumentKeys, resetDatasourceDocumentIds, resyncPending,
            epoch);
    }

    /** The result of the run in progress, updated as it goes. */
    synchronized void progress(ExecutionResult result) {
        if (current != null) {
            current = result;
        }
    }

    /**
     * Records a finished run. Its change tracking state, and the resets it consumed, only apply when no reset happened
     * since it started.
     *
     * @param result     the final result
     * @param start      what the run started from
     * @param tracking   the change tracking state to keep, or null to keep the current one
     * @param persistent whether the failure needs human intervention (the indexer status becomes {@code error})
     */
    synchronized void finish(ExecutionResult result, RunStart start, ChangeTracking tracking, boolean persistent) {
        current = null;
        addHistory(result);
        error = persistent;
        if (start.epoch() != epoch) {
            return;
        }
        if (tracking != null) {
            this.tracking = tracking;
        }
        if (ExecutionResult.SUCCESS.equals(result.status())) {
            if (start.resetDocs()) {
                resetDocumentKeys.removeAll(start.resetDocumentKeys());
                resetDatasourceDocumentIds.removeAll(start.resetDatasourceDocumentIds());
            } else if (start.resync()) {
                resyncPending = false;
            } else {
                fullOverwrite = false;
            }
        }
    }

    /** Reset Indexer: forget the high-water mark and the reset documents, and add the reset entry to the history. */
    synchronized void reset(Instant now) {
        addHistory(ExecutionResult.reset(now, tracking.describe()));
        tracking = ChangeTracking.NONE;
        fullOverwrite = true;
        error = false;
        resyncPending = false;
        resetDocumentKeys.clear();
        resetDatasourceDocumentIds.clear();
        epoch++;
    }

    /** Reset Documents: queue keys and data source ids, replacing the queue when {@code overwrite} is set. */
    synchronized void resetDocuments(List<String> keys, List<String> datasourceIds, boolean overwrite) {
        if (overwrite) {
            resetDocumentKeys.clear();
            resetDatasourceDocumentIds.clear();
        }
        if (keys != null && keys.isEmpty()) {
            resetDocumentKeys.clear();
        } else if (keys != null) {
            resetDocumentKeys.addAll(keys);
        }
        if (datasourceIds != null && datasourceIds.isEmpty()) {
            resetDatasourceDocumentIds.clear();
        } else if (datasourceIds != null) {
            resetDatasourceDocumentIds.addAll(datasourceIds);
        }
    }

    /** Resync: the next run reprocesses every document without moving the high-water mark. */
    synchronized void resync() {
        resyncPending = true;
    }

    /** An update of the indexer definition clears an error status. */
    synchronized void clearError() {
        error = false;
    }

    synchronized boolean inError() {
        return error;
    }

    /** The overall status: {@code error} after a failure that needs intervention, else {@code running}. */
    synchronized String status() {
        return error ? "error" : "running";
    }

    synchronized ExecutionResult lastResult() {
        return current != null ? current : history.peekFirst();
    }

    /** The finished executions, newest first. */
    synchronized List<ExecutionResult> history() {
        return List.copyOf(history);
    }

    synchronized ChangeTracking tracking() {
        return tracking;
    }

    /** The preview {@code currentState} section. */
    synchronized ObjectNode currentState() {
        ObjectNode state = SearchJson.object();
        boolean resetDocs = !resetDocumentKeys.isEmpty() || !resetDatasourceDocumentIds.isEmpty();
        state.put("mode", resetDocs ? ExecutionResult.MODE_RESET_DOCS
            : resyncPending ? ExecutionResult.MODE_RESYNC : ExecutionResult.MODE_ALL_DOCS);
        putText(state, "allDocsInitialTrackingState", tracking.describe());
        putText(state, "allDocsFinalTrackingState", tracking.describe());
        state.putNull("resetDocsInitialTrackingState");
        state.putNull("resetDocsFinalTrackingState");
        state.putNull("resyncInitialTrackingState");
        state.putNull("resyncFinalTrackingState");
        ArrayNode keys = state.putArray("resetDocumentKeys");
        resetDocumentKeys.forEach(keys::add);
        ArrayNode ids = state.putArray("resetDatasourceDocumentIds");
        resetDatasourceDocumentIds.forEach(ids::add);
        return state;
    }

    /** Marks a run that was in progress when the emulator stopped as failed. */
    synchronized void interrupted(Instant now) {
        if (current != null) {
            addHistory(new ExecutionResult(ExecutionResult.TRANSIENT_FAILURE, current.statusDetail(), current.mode(),
                "The indexer run was interrupted before it finished.", current.startTime(), now,
                current.itemsProcessed(), current.itemsFailed(), current.errors(), current.warnings(),
                current.initialTrackingState(), current.initialTrackingState()));
            current = null;
        }
    }

    private void addHistory(ExecutionResult result) {
        history.addFirst(result);
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }

    synchronized ObjectNode toJson() {
        ObjectNode json = SearchJson.object();
        ArrayNode entries = json.putArray("history");
        history.forEach(entry -> entries.add(entry.toJson(true)));
        json.set("tracking", tracking.toJson());
        json.put("fullOverwrite", fullOverwrite);
        json.put("error", error);
        json.put("resyncPending", resyncPending);
        ArrayNode keys = json.putArray("resetDocumentKeys");
        resetDocumentKeys.forEach(keys::add);
        ArrayNode ids = json.putArray("resetDatasourceDocumentIds");
        resetDatasourceDocumentIds.forEach(ids::add);
        return json;
    }

    static IndexerState fromJson(JsonNode json) {
        IndexerState state = new IndexerState();
        List<ExecutionResult> entries = new ArrayList<>();
        json.path("history").forEach(entry -> entries.add(ExecutionResult.fromJson(entry)));
        entries.forEach(state.history::addLast);
        state.tracking = ChangeTracking.fromJson(json.get("tracking"));
        state.fullOverwrite = json.path("fullOverwrite").asBoolean(false);
        state.error = json.path("error").asBoolean(false);
        state.resyncPending = json.path("resyncPending").asBoolean(false);
        json.path("resetDocumentKeys").forEach(key -> state.resetDocumentKeys.add(key.asText()));
        json.path("resetDatasourceDocumentIds").forEach(id -> state.resetDatasourceDocumentIds.add(id.asText()));
        return state;
    }

    private static void putText(ObjectNode json, String field, String value) {
        if (value == null) {
            json.putNull(field);
        } else {
            json.put(field, value);
        }
    }
}
