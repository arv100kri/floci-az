package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A skillset runner for the indexer tests, enabled only by {@link SearchIndexerProfileTest}'s profile. What it does
 * is read from the stored skillset's {@code fake} object: {@code upperCaseInto} names an index field that receives
 * the document's {@code content} upper-cased, {@code projectInto} an index that receives one document per word,
 * {@code skipParent} skips the parent document, {@code warn} adds a warning, {@code failOn} fails a document whose
 * content contains it, and {@code block} waits for {@link #release()}.
 */
@Alternative
@ApplicationScoped
public class FakeSkillsetRunner implements SkillsetRunner {

    private static volatile CountDownLatch gate = new CountDownLatch(0);
    private static volatile ObjectNode lastDocument;
    private static volatile ArrayNode lastOutputFieldMappings;

    static void block() {
        gate = new CountDownLatch(1);
    }

    static void release() {
        gate.countDown();
    }

    static ObjectNode lastDocument() {
        return lastDocument;
    }

    static ArrayNode lastOutputFieldMappings() {
        return lastOutputFieldMappings;
    }

    @Override
    public EnrichmentResult enrich(String serviceName, ObjectNode skillset, ObjectNode document,
                                   ArrayNode outputFieldMappings) {
        lastDocument = document.deepCopy();
        lastOutputFieldMappings = outputFieldMappings.deepCopy();
        JsonNode behavior = skillset.path("fake");
        if (behavior.path("block").asBoolean(false)) {
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the fake skillset runner");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        String content = document.path("content").asText("");
        List<String> warnings = behavior.path("warn").asBoolean(false) ? List.of("Fake warning.") : List.of();
        String failOn = SearchJson.text(behavior, "failOn");
        if (failOn != null && content.contains(failOn)) {
            return new EnrichmentResult(SearchJson.object(), List.of(), false, warnings,
                List.of("Fake skill failed on '" + failOn + "'."));
        }
        ObjectNode indexFields = SearchJson.object();
        String upperCaseInto = SearchJson.text(behavior, "upperCaseInto");
        if (upperCaseInto != null) {
            indexFields.put(upperCaseInto, content.toUpperCase(Locale.ROOT));
        }
        List<ProjectedDocument> projections = new ArrayList<>();
        String projectInto = SearchJson.text(behavior, "projectInto");
        if (projectInto != null) {
            String parent = document.path("id").asText();
            String[] words = content.trim().split("\\s+");
            for (int index = 0; index < words.length; index++) {
                ObjectNode chunk = SearchJson.object();
                chunk.put("chunkId", parent + "_" + index);
                chunk.put("parentId", parent);
                chunk.put("chunk", words[index]);
                projections.add(new ProjectedDocument(projectInto, chunk));
            }
        }
        return new EnrichmentResult(indexFields, projections, behavior.path("skipParent").asBoolean(false), warnings,
            List.of());
    }
}
