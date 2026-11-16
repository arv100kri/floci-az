package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.engine.pipeline.SearchPipeline;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SynonymExpansionTest {

    private static final String DEFINITION = """
        {"name": "places", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "text", "type": "Edm.String", "searchable": true, "synonymMaps": ["geo"]},
          {"name": "plain", "type": "Edm.String", "searchable": true}
        ]}
        """;
    private static final String[] DOCUMENTS = {
        "{\"id\": \"usa\", \"text\": \"trips across the USA\"}",
        "{\"id\": \"states\", \"text\": \"the United States of America\"}",
        "{\"id\": \"wa\", \"text\": \"hiking in WA\"}",
        "{\"id\": \"washington\", \"text\": \"Washington monument\", \"plain\": \"Washington\"}",
        "{\"id\": \"dog\", \"text\": \"a friendly dog\"}",
        "{\"id\": \"dogs\", \"text\": \"dog sitting\", \"plain\": \"canine\"}"
    };
    private static final String RULES = """
        USA, United States, United States of America
        Washington, Wash., WA => WA
        dog, canine, puppy
        """;

    private final Map<String, SynonymMap> maps = new HashMap<>(Map.of("geo",
        new SynonymMap("geo", "\"0x1\"", SolrSynonymRules.parse(RULES))));

    private LiveIndex index() {
        AnalyzerRegistry registry = AnalyzerRegistry.defaultRegistry()
            .forService("svc", (service, name) -> Optional.ofNullable(maps.get(name)));
        SearchIndexDefinition definition = EngineFixtures.definition(DEFINITION);
        LiveIndex index = new LiveIndex(definition, "\"0x1\"", registry);
        for (String document : DOCUMENTS) {
            ObjectNode parsed = (ObjectNode) SearchJson.parse(document.getBytes(StandardCharsets.UTF_8));
            ObjectNode coerced = DocumentCoercer.coerce(parsed, definition);
            index.load(coerced.get("id").asText(), coerced);
        }
        return index;
    }

    private List<String> search(LiveIndex index, SearchRequest request) {
        SearchOutcome outcome = SearchPipeline.standard().run(index, request, EngineSettings.DEFAULTS);
        return outcome.results().stream().map(result -> result.document().get("id").asText()).toList();
    }

    private List<String> search(String text) {
        return search(index(), SearchRequest.builder().search(text).build());
    }

    @Test
    void equivalentTermsExpandToEachOther() {
        assertThat(search("canine"), containsInAnyOrder("dog", "dogs"));
        assertThat(search("puppy"), containsInAnyOrder("dog", "dogs"));
    }

    @Test
    void anExplicitMappingRewritesTheQueryToItsRightSide() {
        assertThat(search("Washington"), containsInAnyOrder("wa", "washington"));
        assertThat(search(index(), SearchRequest.builder().search("Washington").searchFields(List.of("text")).build()),
            containsInAnyOrder("wa"));
        assertThat(search("Wash."), containsInAnyOrder("wa"));
    }

    @Test
    void multiWordSynonymsExpandAsPhrases() {
        assertThat(search("USA"), containsInAnyOrder("usa", "states"));
        assertThat(search("\"United States\""), containsInAnyOrder("usa", "states"));
    }

    @Test
    void fieldsWithoutTheMapAreNotExpanded() {
        assertThat(search(index(), SearchRequest.builder().search("puppy").searchFields(List.of("plain")).build()),
            empty());
    }

    @Test
    void prefixQueriesAreNotExpanded() {
        assertThat(search("pupp*"), empty());
    }

    @Test
    void highlightsCoverTheTermsMatchedThroughSynonyms() {
        Map<String, Map<String, List<String>>> canine = highlights("canine");
        assertThat(canine.get("dog"), is(Map.of("text", List.of("a friendly <em>dog</em>"))));
        assertThat(canine.get("dogs"), is(Map.of("text", List.of("<em>dog</em> sitting"),
            "plain", List.of("<em>canine</em>"))));
        assertThat(highlights("USA").get("states").get("text"),
            contains("the <em>United</em> <em>States</em> <em>of</em> <em>America</em>"));
        Map<String, Map<String, List<String>>> washington = highlights("Washington");
        assertThat(washington.get("wa"), is(Map.of("text", List.of("hiking in <em>WA</em>"))));
        assertThat(washington.get("washington"), is(Map.of("plain", List.of("<em>Washington</em>"))));
    }

    private Map<String, Map<String, List<String>>> highlights(String text) {
        SearchOutcome outcome = SearchPipeline.standard().run(index(), SearchRequest.builder().search(text)
            .highlightFields(List.of("text", "plain")).build(), EngineSettings.DEFAULTS);
        Map<String, Map<String, List<String>>> byKey = new HashMap<>();
        outcome.results().forEach(result -> byKey.put(result.document().get("id").asText(),
            result.hit().highlights()));
        return byKey;
    }

    @Test
    void aMissingSynonymMapFailsTheQueryWith404() {
        LiveIndex index = index();
        maps.clear();
        SearchException error = assertThrows(SearchException.class,
            () -> search(index, SearchRequest.builder().search("dog").build()));
        assertThat(error.status(), is(404));
        assertThat(search(index, SearchRequest.builder().search("dog").searchFields(List.of("plain")).build()),
            empty());
    }
}
