package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.storage.CosmosConnectionStrings;
import io.floci.az.core.storage.StorageConnectionStrings;
import io.floci.az.core.storage.StorageConnectionStrings.Credential;
import io.floci.az.core.storage.StorageConnectionStrings.Resolution;
import io.floci.az.core.storage.StorageConnectionStrings.Resolved;
import io.floci.az.core.storage.StorageConnectionStrings.Service;
import io.floci.az.core.storage.StorageConnectionStrings.Unresolved;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.engine.index.IndexAction;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.indexer.DataSourceDefinition.SoftDeletePolicy;
import io.floci.az.services.search.indexer.IndexerState.RunStart;
import io.floci.az.services.search.indexer.SkillsetRunner.EnrichmentResult;
import io.floci.az.services.search.indexer.SkillsetRunner.ProjectedDocument;
import io.floci.az.services.search.indexer.SourceItem.CrackedItem;
import io.floci.az.services.search.model.IndexActionType;
import io.floci.az.services.search.model.IndexingResult;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchJson;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One indexer execution, following Learn's "Indexers in Azure AI Search" stages: the data source is resolved and,
 * for a managed-identity connection, authorized (and, under strict-network, admitted by the network rules of its
 * storage account: {@link IndexerNetworkAccess}); its items are read (the new and changed ones, through the change
 * tracking state) and cracked; field mappings build each search document; a skillset, when the indexer names one,
 * enriches it through the {@link SkillsetRunner}; and the documents are written to the target index in batches with
 * {@code mergeOrUpload} (or {@code upload} after a reset, "flagged for full overwrite"), the documents a soft-delete
 * column marks with {@code delete}.
 *
 * <p>Index projections follow the content lifecycle of Learn's "Define index projections": before a parent's
 * projections are written, the documents each projection target index holds for that parent (its key in the
 * selector's {@code parentKeyFieldName}) that the new projections do not replace are deleted ("If the source content no
 * longer exists ... the corresponding child document in the search index is deleted"; projected keys hash the parent's
 * content, so changed content projects new keys); and a parent the soft-delete column removes takes its projected
 * documents with it ("the corresponding child documents only get deleted if the deletion is detected by a
 * dataDeletionDetectionPolicy").</p>
 *
 * <p>Item-level failures (a blob that does not crack, a mapping function that fails, an invalid key, a document the
 * index refuses, a skill error) are reported as errors and counted in {@code itemsFailed}; the run stops as a
 * {@code transientFailure} once they exceed {@code maxFailedItems}, or a batch's exceed
 * {@code maxFailedItemsPerBatch}. A successful run moves the high-water mark past every item it went through, failed
 * ones included ("the indexer bypasses the document on future runs"); a failed run leaves it where it was.</p>
 */
final class IndexerRun {

    private static final Logger LOG = Logger.getLogger(IndexerRun.class);
    private static final int MAX_ISSUES = 1000;
    private static final String SKILL_ANCHOR = "#error-could-not-execute-skill";
    private static final String WRITE_ANCHOR = "#error-could-not-mergeorupload--delete-document-to-the-search-index";
    private static final String OUTPUT_ANCHOR = "#warning-could-not-map-output-field-x-to-search-index";

    /**
     * What a run ends with.
     *
     * @param result     the execution result
     * @param tracking   the change tracking state to keep, or null to keep the one the run started from
     * @param persistent whether the failure needs human intervention
     */
    record Outcome(ExecutionResult result, ChangeTracking tracking, boolean persistent) {
    }

    /** What a run resolved before it reads items. */
    private record Prepared(DataSourceDefinition dataSource, LiveIndex index, ObjectNode skillset, SourceReader reader,
                            DocumentMapper mapper, SoftDeletePolicy softDelete, int batchSize,
                            List<ProjectionTarget> projectionTargets) {
    }

    /**
     * An index a selector of the skillset's {@code indexProjections} writes to, and the field its projected documents
     * hold their parent's key in.
     */
    private record ProjectionTarget(String indexName, String parentKeyFieldName) {
    }

    /** The projected documents of one parent key field of one index, by parent key. */
    private record ProjectedChildren(LiveIndex index, String parentKeyField) {
    }

    /** One action waiting for its batch, and the name its failure is reported under. */
    private record Pending(IndexAction action, String issueName) {
    }

    private final IndexerRunner runtime;
    private final SearchServiceInfo service;
    private final String serviceName;
    private final IndexerDefinition indexer;
    private final RunStart start;
    private final BooleanSupplier cancelled;
    private final Consumer<ExecutionResult> progress;
    private final Instant startTime = Instant.now();
    private final List<IndexerIssue> errors = new ArrayList<>();
    private final List<IndexerIssue> warnings = new ArrayList<>();
    private final Map<String, List<Pending>> pending = new LinkedHashMap<>();
    /**
     * The keys of the projected documents each parent has, per projection index and parent key field: read from the
     * index the first time a parent needs them, then kept current as this run queues writes and deletes.
     */
    private final Map<ProjectedChildren, Map<String, Set<String>>> projectedChildren = new HashMap<>();
    private int processed;
    private int failed;
    private int failedInBatch;
    private int inBatch;

    IndexerRun(IndexerRunner runtime, SearchServiceInfo service, IndexerDefinition indexer, RunStart start,
               BooleanSupplier cancelled, Consumer<ExecutionResult> progress) {
        this.runtime = runtime;
        this.service = service;
        this.serviceName = service.name().toLowerCase(Locale.ROOT);
        this.indexer = indexer;
        this.start = start;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    Outcome execute() {
        try {
            Prepared prepared = prepare();
            ChangeTracking tracking = process(prepared);
            return outcome(ExecutionResult.SUCCESS, null, tracking, false);
        } catch (IndexerRunException e) {
            return outcome(e.persistent() ? ExecutionResult.PERSISTENT_FAILURE : ExecutionResult.TRANSIENT_FAILURE,
                e.getMessage(), null, e.persistent());
        } catch (RuntimeException e) {
            LOG.errorv(e, "Indexer {0}/{1} failed unexpectedly", serviceName, indexer.name());
            return outcome(ExecutionResult.TRANSIENT_FAILURE, "An unexpected error occurred while the indexer ran: "
                + e.getMessage(), null, false);
        }
    }

    // ── preparation ───────────────────────────────────────────────────────────────────────────────────────────

    private Prepared prepare() throws IndexerRunException {
        DataSourceDefinition dataSource = runtime.store().dataSource(serviceName, indexer.dataSourceName())
            .orElseThrow(() -> new IndexerRunException("Error with data source: The data source '"
                + indexer.dataSourceName() + "' does not exist."));
        LiveIndex index = runtime.engine().service(serviceName).find(indexer.targetIndexName())
            .orElseThrow(() -> new IndexerRunException("The target index '" + indexer.targetIndexName()
                + "' does not exist."));
        ObjectNode skillset = null;
        if (indexer.skillsetName() != null) {
            skillset = runtime.store().skillset(serviceName, indexer.skillsetName())
                .orElseThrow(() -> new IndexerRunException("The skillset '" + indexer.skillsetName()
                    + "' that the indexer references was not found."));
        }
        if (!dataSource.type().served()) {
            throw new IndexerRunException("floci-az cannot index data sources of type '" + dataSource.type().wireName()
                + "': only azureblob, adlsgen2, azuretable and cosmosdb data sources are read, from floci-az's own "
                + "Blob, Data Lake, Table and Cosmos DB services.", true);
        }
        String connection = dataSource.connectionString();
        if (connection == null || connection.isBlank()) {
            throw new IndexerRunException("Error with data source: The data source '" + dataSource.name()
                + "' has no connection string. Set credentials.connectionString.");
        }
        IndexerParameters parameters = indexer.parameters();
        DocumentMapper mapper = new DocumentMapper(index.definition(), indexer.fieldMappings(),
            dataSource.type().isBlob(), parameters.base64EncodeKeys());
        for (FieldMapping mapping : indexer.fieldMappings()) {
            if (mapper.targetOf(mapping) == null) {
                throw new IndexerRunException("Field mapping specifies target field '" + mapping.effectiveTarget()
                    + "' that doesn't exist in the index.");
            }
        }
        SourceReader reader;
        if (dataSource.type().isBlob()) {
            reader = blobSource(dataSource, connection, parameters);
        } else if (dataSource.type() == DataSourceType.AZURE_TABLE) {
            reader = tableSource(dataSource, connection);
        } else {
            reader = cosmosSource(dataSource, connection);
        }
        String resourceId = resourceId(connection);
        runtime.network().check(service, dataSource, parameters.privateExecution(),
            accountName(dataSource, connection), resourceId.isEmpty() ? null : resourceId);
        return new Prepared(dataSource, index, skillset, reader, mapper, dataSource.softDeletePolicy(),
            parameters.effectiveBatchSize(dataSource.type()), projectionTargets(skillset));
    }

    /** The distinct target index and parent key field of each selector of the skillset's index projections. */
    private static List<ProjectionTarget> projectionTargets(ObjectNode skillset) {
        Set<ProjectionTarget> targets = new LinkedHashSet<>();
        if (skillset != null) {
            for (JsonNode selector : skillset.path("indexProjections").path("selectors")) {
                String index = SearchJson.text(selector, "targetIndexName");
                String parentKey = SearchJson.text(selector, "parentKeyFieldName");
                if (index != null && parentKey != null) {
                    targets.add(new ProjectionTarget(index, parentKey));
                }
            }
        }
        return List.copyOf(targets);
    }

    private SourceReader blobSource(DataSourceDefinition dataSource, String connection, IndexerParameters parameters)
            throws IndexerRunException {
        Resolution resolution = StorageConnectionStrings.resolve(connection, Service.BLOB);
        if (resolution instanceof Unresolved unresolved) {
            throw new IndexerRunException("Error with data source: " + unresolved.reason());
        }
        Resolved resolved = (Resolved) resolution;
        if (dataSource.type() == DataSourceType.ADLS_GEN2 && resolved.containerName() != null) {
            throw new IndexerRunException("Error with data source: ADLS Gen2 indexers can't use container-level SAS "
                + "tokens (ContainerSharedAccessUri). Use an account connection string or a managed identity.");
        }
        String container = dataSource.containerName();
        if (resolved.credential() == Credential.MANAGED_IDENTITY) {
            runtime.identities().check(service, dataSource, resourceId(connection) + "/blobServices/default/containers/"
                + container, ManagedIdentityAccess.BLOB_READ, "Storage Blob Data Reader");
        }
        if (dataSource.nativeBlobSoftDelete()) {
            warn(IndexerIssue.warning(null, "The NativeBlobSoftDeleteDeletionDetectionPolicy of the data source '"
                + dataSource.name() + "' has no effect: floci-az Blob Storage keeps no soft-deleted blobs, so the "
                + "indexer cannot detect deleted blobs. Use a SoftDeleteColumnDeletionDetectionPolicy on a metadata "
                + "property instead.", null, null, null));
        }
        IndexerLimits limits = IndexerLimits.of(service.sku(), parameters.executionEnvironment());
        BlobCracker cracker = new BlobCracker(runtime.blobs(), resolved.accountName(), container, parameters, limits,
            runtime.baseUrl(), "DocumentExtraction." + dataSource.type().wireName() + "." + dataSource.name());
        return new BlobSource(runtime.blobs(), resolved.accountName(), container, dataSource.containerQuery(),
            parameters, cracker);
    }

    private SourceReader tableSource(DataSourceDefinition dataSource, String connection) throws IndexerRunException {
        Resolution resolution = StorageConnectionStrings.resolve(connection, Service.TABLE);
        if (resolution instanceof Unresolved unresolved) {
            throw new IndexerRunException("Error with data source: " + unresolved.reason());
        }
        Resolved resolved = (Resolved) resolution;
        String table = dataSource.containerName();
        if (resolved.credential() == Credential.MANAGED_IDENTITY) {
            runtime.identities().check(service, dataSource, resourceId(connection) + "/tableServices/default/tables/"
                + table, ManagedIdentityAccess.TABLE_READ, "Storage Table Data Reader");
        }
        return new TableSource(runtime.tables(), resolved.accountName(), table, dataSource.containerQuery());
    }

    private SourceReader cosmosSource(DataSourceDefinition dataSource, String connection) throws IndexerRunException {
        CosmosConnectionStrings.Resolution resolution = CosmosConnectionStrings.resolve(connection);
        if (resolution instanceof CosmosConnectionStrings.Unresolved unresolved) {
            throw new IndexerRunException("Error with data source: " + unresolved.reason());
        }
        CosmosConnectionStrings.Resolved resolved = (CosmosConnectionStrings.Resolved) resolution;
        if (resolved.credential() == CosmosConnectionStrings.Credential.MANAGED_IDENTITY) {
            boolean accessToken = "AccessToken".equalsIgnoreCase(pair(connection, "IdentityAuthType"));
            runtime.identities().check(service, dataSource, resourceId(connection), accessToken
                ? ManagedIdentityAccess.COSMOS_ACCOUNT_READ : ManagedIdentityAccess.COSMOS_READ_KEYS,
                "Cosmos DB Account Reader");
        }
        return new CosmosSource(runtime.cosmos(), resolved.accountName(), resolved.databaseName(),
            dataSource.containerName(), dataSource.containerQuery(), dataSource.highWaterMarkColumn());
    }

    /** The {@code ResourceId} of a managed-identity connection string, without a trailing slash. */
    private static String resourceId(String connection) {
        String resourceId = pair(connection, "ResourceId");
        String trimmed = resourceId == null ? "" : resourceId.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** The account a connection string the reader already resolved names. */
    private static String accountName(DataSourceDefinition dataSource, String connection) {
        if (dataSource.type().isCosmos()) {
            return CosmosConnectionStrings.resolve(connection) instanceof CosmosConnectionStrings.Resolved resolved
                ? resolved.accountName() : null;
        }
        Service storage = dataSource.type() == DataSourceType.AZURE_TABLE ? Service.TABLE : Service.BLOB;
        return StorageConnectionStrings.resolve(connection, storage) instanceof Resolved resolved
            ? resolved.accountName() : null;
    }

    /** The value of {@code key} in a {@code key=value;...} connection string, matched ignoring case; or null. */
    private static String pair(String connection, String key) {
        for (String segment : connection.split(";")) {
            int equals = segment.indexOf('=');
            if (equals > 0 && segment.substring(0, equals).trim().equalsIgnoreCase(key)) {
                return segment.substring(equals + 1).trim();
            }
        }
        return null;
    }

    // ── processing ────────────────────────────────────────────────────────────────────────────────────────────

    /** Processes every item; returns the change tracking state to keep, or null to keep the starting one. */
    private ChangeTracking process(Prepared prepared) throws IndexerRunException {
        boolean everything = start.resetDocs() || start.resync();
        List<SourceItem> items = prepared.reader().items(start.tracking(), everything);
        ChangeTracking.Builder tracker = new ChangeTracking.Builder(start.tracking(),
            prepared.reader().timestampTracking());
        for (SourceItem item : items) {
            checkCancelled();
            tracker.track(item.trackingValue(), item.trackingIdentity());
            if (item.excluded()) {
                continue;
            }
            boolean selectedById = start.resetDatasourceDocumentIds().contains(item.datasourceDocumentId());
            boolean reported = !start.resetDocs() || selectedById;
            CrackedItem cracked = item.cracker().crack();
            if (reported) {
                cracked.warnings().forEach(this::warn);
            }
            if (cracked.error() != null) {
                if (reported) {
                    processed++;
                    fail(prepared, cracked.error());
                    endDocument(prepared);
                }
                continue;
            }
            for (ObjectNode document : cracked.documents()) {
                processDocument(prepared, document, cracked.oneToMany(), selectedById);
            }
        }
        flush(prepared);
        return everything ? null : tracker.build();
    }

    private void processDocument(Prepared prepared, ObjectNode document, boolean oneToMany, boolean selectedById)
            throws IndexerRunException {
        DocumentMapper.Mapped mapped;
        try {
            mapped = prepared.mapper().map(document);
        } catch (ItemFailure failure) {
            if (!start.resetDocs() || selectedById) {
                processed++;
                fail(prepared, failure.issue());
                endDocument(prepared);
            }
            return;
        }
        String key = mapped.key();
        if (start.resetDocs() && !selectedById && !start.resetDocumentKeys().contains(key)) {
            return;
        }
        processed++;
        String indexName = prepared.index().name();
        SoftDeletePolicy softDelete = prepared.softDelete();
        if (softDelete != null && !oneToMany && markedDeleted(document, softDelete)) {
            ObjectNode keyOnly = SearchJson.object();
            keyOnly.put(prepared.index().definition().keyField().name(), key);
            queue(indexName, new IndexAction(IndexActionType.DELETE, key, keyOnly, null),
                "Projection.SearchIndex.Delete." + indexName);
            replaceProjections(prepared, key, Map.of());
            endDocument(prepared);
            return;
        }
        ObjectNode fields = mapped.fields();
        if (prepared.skillset() != null && !enrich(prepared, mapped, fields)) {
            endDocument(prepared);
            return;
        }
        try {
            ObjectNode searchDocument = prepared.mapper().toSearchDocument(key, fields, indexName);
            IndexActionType type = start.fullOverwrite() ? IndexActionType.UPLOAD : IndexActionType.MERGE_OR_UPLOAD;
            queue(indexName, new IndexAction(type, key, searchDocument, null),
                "Projection.SearchIndex." + (type == IndexActionType.UPLOAD ? "Upload." : "MergeOrUpload.")
                    + indexName);
        } catch (ItemFailure failure) {
            fail(prepared, failure.issue());
        }
        endDocument(prepared);
    }

    /**
     * Runs the skillset over one document: merges its index fields into {@code fields}, queues its projections and
     * reports its warnings and errors. Returns false when the document must not be indexed itself (it failed, or the
     * projections replace it).
     */
    private boolean enrich(Prepared prepared, DocumentMapper.Mapped mapped, ObjectNode fields)
            throws IndexerRunException {
        String issueName = "Enrichment.Skillset." + indexer.skillsetName();
        ObjectNode document = mapped.enrichment();
        document.put(SkillsetRunner.DOCUMENT_KEY_NODE, mapped.key());
        EnrichmentResult result;
        try {
            result = runtime.skills().enrich(serviceName, prepared.skillset().deepCopy(), document,
                indexer.outputFieldMappings().deepCopy());
        } catch (RuntimeException e) {
            LOG.errorv(e, "The skillset {0} of indexer {1}/{2} failed", indexer.skillsetName(), serviceName,
                indexer.name());
            fail(prepared, IndexerIssue.error(mapped.key(), 500, "Could not execute skill because of an unexpected "
                + "error.", issueName, e.getMessage(), SKILL_ANCHOR));
            return false;
        }
        for (String warning : result.warnings()) {
            warn(IndexerIssue.warning(mapped.key(), warning, issueName, null, null));
        }
        if (!result.errors().isEmpty()) {
            for (String error : result.errors()) {
                addError(IndexerIssue.error(mapped.key(), 400, error, issueName, null, SKILL_ANCHOR));
            }
            failed++;
            failedInBatch++;
            checkLimits(prepared);
            return false;
        }
        mergeIndexFields(prepared, result.indexFields(), fields, mapped.key());
        Map<String, Set<String>> projectedKeys = new HashMap<>();
        for (ProjectedDocument projection : result.projections()) {
            String key = queueProjection(prepared, projection, mapped.key());
            if (key != null) {
                projectedKeys.computeIfAbsent(projection.targetIndexName(), ignored -> new HashSet<>()).add(key);
            }
        }
        replaceProjections(prepared, mapped.key(), projectedKeys);
        return !result.skipParentDocument();
    }

    private void mergeIndexFields(Prepared prepared, ObjectNode indexFields, ObjectNode fields, String key) {
        if (indexFields == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = indexFields.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            SearchFieldDefinition field = topLevelField(prepared.index(), entry.getKey());
            if (field == null) {
                warn(IndexerIssue.warning(key, "Could not map output field '" + entry.getKey() + "' to search index.",
                    "Projection.SearchIndex.OutputFieldMapping." + entry.getKey(), "The index '"
                    + prepared.index().name() + "' has no field of that name.", OUTPUT_ANCHOR));
            } else if (!field.key()) {
                fields.set(field.name(), entry.getValue());
            }
        }
    }

    /**
     * Queues one projected document; returns its key, or null when it has none. A key is returned even when the
     * document is refused, so a document the parent projected before under the same key is kept.
     */
    private String queueProjection(Prepared prepared, ProjectedDocument projection, String parentKey)
            throws IndexerRunException {
        String indexName = projection.targetIndexName();
        String issueName = "Projection.SearchIndex.MergeOrUpload." + indexName;
        LiveIndex target = runtime.engine().service(serviceName).find(indexName).orElse(null);
        if (target == null) {
            fail(prepared, IndexerIssue.error(parentKey, 404, "The index '" + indexName + "' that an index projection "
                + "targets does not exist.", issueName, null, WRITE_ANCHOR));
            return null;
        }
        ObjectNode normalized = SearchJson.object();
        Iterator<Map.Entry<String, JsonNode>> entries = projection.document().fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            SearchFieldDefinition field = topLevelField(target, entry.getKey());
            if (field != null) {
                normalized.set(field.name(), entry.getValue());
            }
        }
        String keyName = target.definition().keyField().name();
        JsonNode keyValue = normalized.get(keyName);
        if (IndexerJson.isNull(keyValue) || keyValue.asText().isEmpty()) {
            fail(prepared, IndexerIssue.error("", 400, "Document key cannot be missing or empty.", issueName,
                "An index projection of the document '" + parentKey + "' has no value for the key field '" + keyName
                + "'.", WRITE_ANCHOR));
            return null;
        }
        String key = keyValue.asText();
        try {
            ObjectNode document = DocumentMapper.toSearchDocument(target.definition(), key, normalized, indexName);
            queue(target.name(), new IndexAction(IndexActionType.MERGE_OR_UPLOAD, key, document,
                DocumentMapper.keyError(key)), issueName);
        } catch (ItemFailure failure) {
            fail(prepared, failure.issue());
        }
        return key;
    }

    /**
     * Makes {@code projectedKeys} (by target index) the projected documents of the parent {@code parentKey}: queues the
     * deletion of every other document a projection target index holds for that parent in its parent key field.
     * The parent's own document, when it lives in a projection target index, is left alone.
     */
    private void replaceProjections(Prepared prepared, String parentKey, Map<String, Set<String>> projectedKeys) {
        for (ProjectionTarget target : prepared.projectionTargets()) {
            LiveIndex index = runtime.engine().service(serviceName).find(target.indexName()).orElse(null);
            SearchFieldDefinition parentField = index == null ? null
                : topLevelField(index, target.parentKeyFieldName());
            if (parentField == null) {
                continue;
            }
            Map<String, Set<String>> children = projectedChildren.computeIfAbsent(
                new ProjectedChildren(index, parentField.name()),
                key -> readChildren(key.index(), key.parentKeyField()));
            Set<String> kept = projectedKeys.getOrDefault(target.indexName(), Set.of());
            String keyField = index.definition().keyField().name();
            for (String child : children.getOrDefault(parentKey, Set.of())) {
                boolean parentDocument = index == prepared.index() && child.equals(parentKey);
                if (!kept.contains(child) && !parentDocument) {
                    ObjectNode keyOnly = SearchJson.object();
                    keyOnly.put(keyField, child);
                    queue(index.name(), new IndexAction(IndexActionType.DELETE, child, keyOnly, null),
                        "Projection.SearchIndex.Delete." + index.name());
                }
            }
            if (kept.isEmpty()) {
                children.remove(parentKey);
            } else {
                children.put(parentKey, new HashSet<>(kept));
            }
        }
    }

    /** The keys of the documents of {@code index}, grouped by the value of their {@code parentKeyField}. */
    private static Map<String, Set<String>> readChildren(LiveIndex index, String parentKeyField) {
        return index.read(live -> {
            Map<String, Set<String>> byParent = new HashMap<>();
            for (int ordinal = 0; ordinal < live.ordinalLimit(); ordinal++) {
                IndexedDocument document = live.document(ordinal);
                JsonNode parent = document == null ? null : document.source().get(parentKeyField);
                if (parent != null && parent.isTextual()) {
                    byParent.computeIfAbsent(parent.textValue(), ignored -> new HashSet<>()).add(document.key());
                }
            }
            return byParent;
        });
    }

    private static SearchFieldDefinition topLevelField(LiveIndex index, String name) {
        for (SearchFieldDefinition field : index.definition().fields()) {
            if (field.name().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static boolean markedDeleted(ObjectNode document, SoftDeletePolicy policy) {
        JsonNode value = IndexerJson.property(document, policy.column());
        return value != null && value.isValueNode() && !value.isNull()
            && value.asText().equalsIgnoreCase(policy.markerValue());
    }

    // ── batches ───────────────────────────────────────────────────────────────────────────────────────────────

    private void queue(String indexName, IndexAction action, String issueName) {
        pending.computeIfAbsent(indexName, ignored -> new ArrayList<>()).add(new Pending(action, issueName));
    }

    /** Counts a processed document toward the batch, and writes the batch once it is full. */
    private void endDocument(Prepared prepared) throws IndexerRunException {
        inBatch++;
        if (inBatch >= prepared.batchSize()) {
            flush(prepared);
        }
    }

    private void flush(Prepared prepared) throws IndexerRunException {
        checkCancelled();
        for (Map.Entry<String, List<Pending>> batch : pending.entrySet()) {
            LiveIndex target = runtime.engine().service(serviceName).find(batch.getKey()).orElse(null);
            if (batch.getKey().equals(prepared.index().name()) && target != prepared.index()) {
                throw new IndexerRunException("The target index '" + batch.getKey() + "' was deleted while the "
                    + "indexer ran.");
            }
            if (target == null) {
                for (Pending item : batch.getValue()) {
                    countFailure(IndexerIssue.error(item.action().key(), 404, "The index '" + batch.getKey()
                        + "' does not exist.", item.issueName(), null, WRITE_ANCHOR));
                }
                continue;
            }
            List<IndexAction> actions = batch.getValue().stream().map(Pending::action).toList();
            List<IndexingResult> results = runtime.engine().index(serviceName, target, actions);
            for (int position = 0; position < results.size(); position++) {
                IndexingResult result = results.get(position);
                if (!result.status()) {
                    Pending item = batch.getValue().get(position);
                    countFailure(IndexerIssue.error(result.key(), result.statusCode(), "Could not '"
                        + verb(item.action().type()) + "' document to the search index.", item.issueName(),
                        result.errorMessage(), WRITE_ANCHOR));
                }
            }
        }
        pending.clear();
        inBatch = 0;
        checkLimits(prepared);
        failedInBatch = 0;
        progress.accept(snapshot());
    }

    private static String verb(IndexActionType type) {
        return switch (type) {
            case UPLOAD -> "Upload";
            case MERGE -> "Merge";
            case MERGE_OR_UPLOAD -> "MergeOrUpload";
            case DELETE -> "Delete";
        };
    }

    // ── failures ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Records a failed item and stops the run when the failures exceed the indexer's limits. */
    private void fail(Prepared prepared, IndexerIssue error) throws IndexerRunException {
        countFailure(error);
        checkLimits(prepared);
    }

    private void countFailure(IndexerIssue error) {
        addError(error);
        failed++;
        failedInBatch++;
    }

    private void checkLimits(Prepared prepared) throws IndexerRunException {
        int limit = indexer.parameters().failureLimit();
        if (limit >= 0 && failed > limit) {
            throw new IndexerRunException("The indexer stopped after " + failed + " failed items: maxFailedItems "
                + "allows " + limit + ". Raise maxFailedItems (-1 for no limit) to index the other items despite the "
                + "failures.");
        }
        int batchLimit = indexer.parameters().batchFailureLimit();
        if (batchLimit >= 0 && failedInBatch > batchLimit) {
            throw new IndexerRunException("The indexer stopped after " + failedInBatch + " failed items in a batch of "
                + prepared.batchSize() + ": maxFailedItemsPerBatch allows " + batchLimit + ".");
        }
    }

    private void checkCancelled() throws IndexerRunException {
        if (cancelled.getAsBoolean()) {
            throw new IndexerRunException("The indexer run was cancelled: the emulator is resetting or shutting "
                + "down.");
        }
    }

    private void addError(IndexerIssue error) {
        if (errors.size() < MAX_ISSUES) {
            errors.add(error);
        }
    }

    private void warn(IndexerIssue warning) {
        if (warnings.size() < MAX_ISSUES) {
            warnings.add(warning);
        }
    }

    // ── results ───────────────────────────────────────────────────────────────────────────────────────────────

    private ExecutionResult snapshot() {
        return new ExecutionResult(ExecutionResult.IN_PROGRESS, statusDetail(), mode(), null, startTime, null,
            processed, failed, errors, warnings, start.tracking().describe(), null);
    }

    private Outcome outcome(String status, String errorMessage, ChangeTracking tracking, boolean persistent) {
        ChangeTracking finalTracking = tracking == null ? start.tracking() : tracking;
        ExecutionResult result = new ExecutionResult(status, statusDetail(), mode(), errorMessage, startTime,
            Instant.now(), processed, failed, errors, warnings, start.tracking().describe(), finalTracking.describe());
        return new Outcome(result, tracking, persistent);
    }

    private String statusDetail() {
        return start.resetDocs() ? "resetDocs" : start.resync() ? "resync" : null;
    }

    private String mode() {
        return start.resetDocs() ? ExecutionResult.MODE_RESET_DOCS
            : start.resync() ? ExecutionResult.MODE_RESYNC : ExecutionResult.MODE_ALL_DOCS;
    }
}
