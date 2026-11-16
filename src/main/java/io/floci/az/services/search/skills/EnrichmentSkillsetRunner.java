package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.indexer.SkillsetRunner;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The AI enrichment pipeline the indexer runs a skillset with: the {@link SkillsetRunner} floci-az provides, preferred
 * over the {@code @DefaultBean} fallback that fails every document. See {@link EnrichmentPipeline} for what it does.
 *
 * <p>The indexer passes the {@code /document} node it built, with {@code /document/AzureSearch_DocumentKey} set to the
 * document key, which index projections write to their {@code parentKeyFieldName} and build projected keys from.</p>
 */
@ApplicationScoped
public class EnrichmentSkillsetRunner implements SkillsetRunner {

    /**
     * The enrichment node, {@code /document/AzureSearch_DocumentKey}, that holds the document key: the indexer sets it
     * on the {@code /document} node it passes, and index projections read it.
     */
    public static final String DOCUMENT_KEY_NODE = SkillsetRunner.DOCUMENT_KEY_NODE;

    private static final Logger LOG = Logger.getLogger(EnrichmentSkillsetRunner.class);

    private final SkillHttp http;
    private final EnrichmentPipeline pipeline;

    @Inject
    public EnrichmentSkillsetRunner(EmulatorConfig config, SearchEngine engine) {
        this.http = new SkillHttp();
        this.pipeline = new EnrichmentPipeline(http, config.services().search().modelEndpoint().orElse(null),
            (service, index) -> engine.service(service).find(index)
                .map(live -> live.definition().keyField().name())
                .orElse(null));
    }

    @PreDestroy
    void close() {
        http.close();
    }

    @Override
    public EnrichmentResult enrich(String serviceName, ObjectNode skillset, ObjectNode document,
                                   ArrayNode outputFieldMappings) {
        try {
            return pipeline.run(serviceName, skillset, document, outputFieldMappings);
        } catch (RuntimeException e) {
            LOG.errorv(e, "Enriching a document of search service {0} failed", serviceName);
            return new EnrichmentResult(JsonNodeFactory.instance.objectNode(), List.of(), false, List.of(),
                List.of("Enrichment: An unexpected error occurred while enriching the document: "
                    + WebApiSkill.describe(e)));
        }
    }
}
