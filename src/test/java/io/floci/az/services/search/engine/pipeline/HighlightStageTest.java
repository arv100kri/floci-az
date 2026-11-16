package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.HotelsCorpus;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HighlightStageTest {

    private static final LiveIndex HOTELS = HotelsCorpus.index();

    private static Map<String, Map<String, List<String>>> highlights(SearchRequest request) {
        return highlights(HOTELS, request);
    }

    private static Map<String, Map<String, List<String>>> highlights(LiveIndex index, SearchRequest request) {
        Map<String, Map<String, List<String>>> byKey = new LinkedHashMap<>();
        HotelsCorpus.run(index, request).results().forEach(result ->
            byKey.put(result.document().get("id").asText(), result.hit().highlights()));
        return byKey;
    }

    private static SearchRequest.Builder query(String search, String... fields) {
        return SearchRequest.builder().search(search).highlightFields(List.of(fields));
    }

    @Test
    void matchedTermsAreWrappedInTheDefaultOrCustomTags() {
        Map<String, Map<String, List<String>>> pool = highlights(query("pool", "description").build());
        assertThat(pool.get("1"), hasEntry("description", List.of("Luxury resort with a <em>pool</em> and an ocean view")));
        assertThat(pool.get("3"), hasEntry("description",
            List.of("Modern hotel in the city center with a rooftop <em>pool</em>")));
        Map<String, Map<String, List<String>>> bold = highlights(query("pool", "description")
            .highlightPreTag("<b>").highlightPostTag("</b>").build());
        assertThat(bold.get("1").get("description"), contains("Luxury resort with a <b>pool</b> and an ocean view"));
    }

    @Test
    void collectionsHighlightTheMatchingValuesOnly() {
        assertThat(highlights(query("pool", "description", "tags").build()).get("1"),
            hasEntry("tags", List.of("<em>pool</em>")));
    }

    @Test
    void onlyCompletePhrasesAreHighlighted() {
        assertThat(highlights(query("\"ocean view\"", "name", "description").build()).get("1"), is(Map.of(
            "name", List.of("<em>Ocean</em> <em>View</em> Resort"),
            "description", List.of("Luxury resort with a pool and an <em>ocean</em> <em>view</em>"))));
        Map<String, List<String>> harbor = highlights(query("\"ocean view\" OR harbor", "description")
            .queryType(QueryType.FULL).build()).get("6");
        assertThat(harbor.get("description"), contains("Small boutique hotel on the <em>harbor</em> with a view"));
    }

    @Test
    void clausesHighlightOnlyTheFieldsTheySearch() {
        Map<String, List<String>> fielded = highlights(query("name:hotel", "name", "description")
            .queryType(QueryType.FULL).build()).get("3");
        assertThat(fielded, is(Map.of("name", List.of("City Center <em>Hotel</em>"))));
        assertThat(highlights(query("pool", "description").searchFields(List.of("tags")).build()).get("3"),
            nullValue());
        assertThat(highlights(query("pool -rooftop", "description").queryType(QueryType.FULL).build()).get("1")
            .get("description"), contains("Luxury resort with a <em>pool</em> and an ocean view"));
    }

    @Test
    void prefixWildcardAndFuzzyMatchesAreHighlighted() {
        assertThat(highlights(query("air*", "description").build()).get("2").get("description"),
            contains("Cheap motel near the <em>airport</em> with free parking"));
        assertThat(highlights(query("montain~", "name").queryType(QueryType.FULL).build()).get("4").get("name"),
            contains("<em>Mountain</em> Lodge"));
        assertThat(highlights(query("/[mh]otel/", "description").queryType(QueryType.FULL).build()).get("2")
            .get("description"), contains("Cheap <em>motel</em> near the airport with free parking"));
    }

    @Test
    void longValuesAreCutIntoTheBestSentences() {
        LiveIndex index = EngineFixtures.index("""
            {"name": "long", "fields": [{"name": "id", "type": "Edm.String", "key": true},
              {"name": "text", "type": "Edm.String"}]}
            """, "{\"id\":\"1\",\"text\":\"The pool is open daily from early morning. Breakfast is served on the "
            + "terrace at eight every day of the week. Guests love the pool bar and the pool towels. The lobby has "
            + "a fireplace, a library and a quiet reading corner for rainy afternoons.\"}");
        Map<String, List<String>> all = highlights(index, query("pool", "text").build()).get("1");
        assertThat(all.get("text"), contains("Guests love the <em>pool</em> bar and the <em>pool</em> towels.",
            "The <em>pool</em> is open daily from early morning."));
        assertThat(highlights(index, query("pool", "text-1").build()).get("1").get("text"),
            contains("Guests love the <em>pool</em> bar and the <em>pool</em> towels."));
    }

    @Test
    void matchAllQueriesHighlightNothing() {
        assertThat(highlights(query("*", "description").build()).get("1"), nullValue());
        assertThat(highlights(query("pool", "description").build()).get("1"), not(nullValue()));
    }

    @Test
    void invalidHighlightRequestsAreBadRequests() {
        assertThrows(SearchException.class, () -> highlights(query("pool", "category").build()));
        assertThrows(SearchException.class, () -> highlights(query("pool", "nope").build()));
        assertThrows(SearchException.class, () -> highlights(query("pool", "rating").build()));
        assertThrows(SearchException.class, () -> highlights(query("pool", "description")
            .highlightPreTag("<b>").build()));
    }
}
