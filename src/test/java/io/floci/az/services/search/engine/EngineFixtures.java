package io.floci.az.services.search.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.analysis.AnalyzerRegistry;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.IndexDefinitionParser;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.nio.charset.StandardCharsets;

/** Builds engine objects from JSON for the engine's unit tests. */
public final class EngineFixtures {

    private EngineFixtures() {
    }

    public static SearchIndexDefinition definition(String json) {
        return IndexDefinitionParser.parse(SearchJson.parse(json.getBytes(StandardCharsets.UTF_8)), null,
            AnalyzerRegistry.defaultRegistry());
    }

    /** An index holding {@code documents} (JSON objects), loaded in order. */
    public static LiveIndex index(String definitionJson, String... documents) {
        SearchIndexDefinition definition = definition(definitionJson);
        LiveIndex index = new LiveIndex(definition, Etags.next(), AnalyzerRegistry.defaultRegistry());
        String key = definition.keyField().name();
        for (String document : documents) {
            ObjectNode parsed = (ObjectNode) SearchJson.parse(document.getBytes(StandardCharsets.UTF_8));
            ObjectNode coerced = DocumentCoercer.coerce(parsed, definition);
            index.load(coerced.get(key).asText(), coerced);
        }
        return index;
    }
}
