package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.indexer.SkillsetRunner;
import io.floci.az.services.search.indexer.SkillsetRunner.ProjectedDocument;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.skills.EnrichedDocument.Located;
import io.floci.az.services.search.skills.EnrichedDocument.PathValue;
import io.floci.az.services.search.skills.SkillDefinition.InputEntry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Applies a skillset's {@code indexProjections}: each selector turns every node of its {@code sourceContext} into a
 * document for {@code targetIndexName}, with the selector's {@code mappings} evaluated at that node. Each projected
 * document carries the parent document's key in {@code parentKeyFieldName}, and a key made of the documented segments:
 * a hash of the parent document (so it changes when the parent changes), the parent key, and the path of the source
 * context node ({@code 3f1c2a9b8d7e_parentKey_pages_0}). The hash is computed from the parent's content, so the same
 * document always projects the same keys.
 *
 * <p>The parent key is the enrichment tree's {@code /document/AzureSearch_DocumentKey} node, which the indexer sets to
 * the document key. Without it, the value the document has for the target index's key field is used (the parent
 * document's key when the projection targets the indexer's own index), and failing that a hash of the document.</p>
 */
final class IndexProjector {

    /** The enrichment node that holds the parent document's key. */
    static final String DOCUMENT_KEY_NODE = SkillsetRunner.DOCUMENT_KEY_NODE;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int HASH_LENGTH = 12;

    private IndexProjector() {
    }

    /**
     * Projects {@code document}.
     *
     * @param document    the enriched document
     * @param projections the skillset's {@code indexProjections}, or null
     * @param original    the {@code /document} node the indexer passed in, which the key hash is computed from
     * @param keyFields   the name of the key field of an index, or null when the index does not exist
     * @param messages    where errors go
     */
    static Projection project(EnrichedDocument document, JsonNode projections, ObjectNode original,
                              Function<String, String> keyFields, SkillMessages messages) {
        List<ProjectedDocument> projected = new ArrayList<>();
        if (projections == null || !(projections.get("selectors") instanceof ArrayNode selectors)
                || selectors.isEmpty()) {
            return new Projection(projected, false);
        }
        JsonNode parameters = projections.get("parameters");
        boolean skipParent = "skipIndexingParentDocuments".equalsIgnoreCase(
            parameters == null ? null : SkillDefinition.text(parameters, "projectionMode"));
        String hash = contentHash(original).substring(0, HASH_LENGTH);
        InputEvaluator evaluator = new InputEvaluator(document);
        for (JsonNode selector : selectors) {
            String targetIndex = SkillDefinition.text(selector, "targetIndexName");
            String operation = "Projection.SearchIndex.IndexProjection." + targetIndex;
            String keyField = targetIndex == null ? null : keyFields.apply(targetIndex);
            if (keyField == null) {
                messages.error(operation, "Could not project document. The index projection's target index '"
                    + targetIndex + "' does not exist.");
                continue;
            }
            String parentKeyField = SkillDefinition.text(selector, "parentKeyFieldName");
            EnrichmentPath sourceContext;
            List<InputEntry> mappings;
            try {
                sourceContext = SkillDefinition.parsePath(SkillDefinition.text(selector, "sourceContext"),
                    "sourceContext");
                mappings = InputEntry.parseAll(selector.get("mappings"), sourceContext);
            } catch (IllegalArgumentException e) {
                messages.error(operation, "Could not project document. The index projection is invalid: "
                    + e.getMessage() + ".");
                continue;
            }
            String parentKey = parentKey(document, original, keyField);
            for (Located instance : document.instances(sourceContext)) {
                ObjectNode projection = NODES.objectNode();
                projection.put(keyField, hash + "_" + parentKey + "_" + keySuffix(instance));
                if (parentKeyField != null) {
                    projection.put(parentKeyField, parentKey);
                }
                for (InputEntry mapping : mappings) {
                    PathValue value = evaluator.evaluate(mapping, instance, sourceContext);
                    if (!value.isMissing()) {
                        projection.set(mapping.name(), value.value().deepCopy());
                    }
                }
                projected.add(new ProjectedDocument(targetIndex, projection));
            }
        }
        return new Projection(projected, skipParent);
    }

    /** The parent document's key: the documented enrichment node, else the target key field's value, else a hash. */
    static String parentKey(EnrichedDocument document, ObjectNode original, String targetKeyField) {
        EnrichmentNode node = document.root().child(DOCUMENT_KEY_NODE).node();
        if (node != null && node.value() != null && node.value().isValueNode() && !node.isNull()) {
            return node.value().asText();
        }
        JsonNode keyValue = original == null ? null : original.get(targetKeyField);
        if (keyValue != null && keyValue.isTextual() && !keyValue.textValue().isEmpty()) {
            return keyValue.textValue();
        }
        return contentHash(original).substring(0, 32);
    }

    /** The SHA-256 of the document's JSON with object members sorted, so member order does not change it. */
    static String contentHash(ObjectNode document) {
        return hash(SearchJson.write(canonical(document == null ? NODES.objectNode() : document)));
    }

    private static JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = NODES.objectNode();
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.sort(null);
            for (String name : names) {
                sorted.set(name, canonical(object.get(name)));
            }
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = NODES.arrayNode();
            array.forEach(element -> copy.add(canonical(element)));
            return copy;
        }
        return node;
    }

    /** The source context node's path below {@code /document}, as key characters: {@code pages_0}. */
    private static String keySuffix(Located instance) {
        String path = instance.node().path();
        String relative = path.equals("/document") ? "document" : path.substring("/document/".length());
        StringBuilder suffix = new StringBuilder(relative.length());
        for (int index = 0; index < relative.length(); index++) {
            char character = relative.charAt(index);
            boolean allowed = Character.isLetterOrDigit(character) && character < 128 || character == '-'
                || character == '=';
            suffix.append(allowed ? character : '_');
        }
        return suffix.toString();
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * The documents projected from one enriched document.
     *
     * @param documents  the projected documents, selector by selector, in source context order
     * @param skipParent whether {@code projectionMode} is {@code skipIndexingParentDocuments}
     */
    record Projection(List<ProjectedDocument> documents, boolean skipParent) {

        Projection {
            documents = List.copyOf(documents);
        }
    }
}
