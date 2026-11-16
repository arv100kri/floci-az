package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The skillset runner used until the enrichment pipeline is present: every document fails with one error. */
@DefaultBean
@ApplicationScoped
public class UnavailableSkillsetRunner implements SkillsetRunner {

    @Override
    public EnrichmentResult enrich(String serviceName, ObjectNode skillset, ObjectNode document,
                                   ArrayNode outputFieldMappings) {
        return new EnrichmentResult(JsonNodeFactory.instance.objectNode(), List.of(), false, List.of(),
            List.of("Skillsets are not available on this emulator build."));
    }
}
