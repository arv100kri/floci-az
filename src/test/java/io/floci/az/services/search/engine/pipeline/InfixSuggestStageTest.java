package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.HotelsCorpus;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InfixSuggestStageTest {

    private static final LiveIndex HOTELS = HotelsCorpus.index();
    private static final InfixSuggestStage STAGE = new InfixSuggestStage();

    private static ObjectNode parameters(String json) {
        return (ObjectNode) SearchJson.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode suggest(String json) {
        return HOTELS.read(live -> STAGE.suggest(live, parameters(json)));
    }

    private static ObjectNode autocomplete(String json) {
        return HOTELS.read(live -> STAGE.autocomplete(live, parameters(json)));
    }

    private static List<String> values(ObjectNode body, String property) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : body.get("value")) {
            values.add(item.get(property).asText());
        }
        return values;
    }

    @Test
    void suggestionsMatchTheStartOfAnyWordOfASourceField() {
        ObjectNode budget = suggest("{\"search\": \"bud\", \"suggesterName\": \"sg\"}");
        assertThat(budget.get("value").toString(), is("[{\"@search.text\":\"Budget Inn\",\"id\":\"2\"}]"));
        assertThat(values(suggest("{\"search\": \"b\", \"suggesterName\": \"sg\"}"), "@search.text"),
            containsInAnyOrder("Budget Inn", "Harbor Boutique"));
        assertThat(values(suggest("{\"search\": \"budget i\", \"suggesterName\": \"sg\"}"), "@search.text"),
            contains("Budget Inn"));
        assertThat(values(suggest("{\"search\": \"inn bud\", \"suggesterName\": \"sg\"}"), "@search.text"),
            contains("Budget Inn"));
        assertThat(values(suggest("{\"search\": \"budget x\", \"suggesterName\": \"sg\"}"), "@search.text"),
            empty());
        assertThat(values(suggest("{\"search\": \"lodge\", \"suggesterName\": \"sg\"}"), "id"), contains("4"));
    }

    @Test
    void suggestionsHonourSelectFilterOrderTopFuzzyAndHighlights() {
        assertThat(suggest("{\"search\": \"bud\", \"suggesterName\": \"sg\", \"select\": \"id,category\"}")
            .get("value").get(0).get("category").asText(), is("Budget"));
        assertThat(values(suggest("{\"search\": \"bidget\", \"suggesterName\": \"sg\", \"fuzzy\": true}"),
            "@search.text"), contains("Budget Inn"));
        assertThat(values(suggest("{\"search\": \"bidget\", \"suggesterName\": \"sg\"}"), "@search.text"), empty());
        assertThat(values(suggest("{\"search\": \"b\", \"suggesterName\": \"sg\", \"filter\": \"category eq "
            + "'Boutique'\"}"), "@search.text"), contains("Harbor Boutique"));
        assertThat(values(suggest("{\"search\": \"b\", \"suggesterName\": \"sg\", \"orderby\": \"id desc\"}"), "id"),
            contains("6", "2"));
        assertThat(values(suggest("{\"search\": \"b\", \"suggesterName\": \"sg\", \"orderby\": \"id desc\", "
            + "\"top\": 1}"), "id"), contains("6"));
        assertThat(values(suggest("{\"search\": \"bud\", \"suggesterName\": \"sg\", \"highlightPreTag\": \"<b>\", "
            + "\"highlightPostTag\": \"</b>\"}"), "@search.text"), contains("<b>Bud</b>get Inn"));
        assertThat(values(suggest("{\"search\": \"bidget\", \"suggesterName\": \"sg\", \"fuzzy\": \"true\", "
            + "\"highlightPreTag\": \"<b>\", \"highlightPostTag\": \"</b>\", \"top\": \"3\"}"), "@search.text"),
            contains("<b>Budget</b> Inn"));
        ObjectNode covered = suggest("{\"search\": \"bud\", \"suggesterName\": \"sg\", \"minimumCoverage\": 80}");
        assertThat(covered.get("@search.coverage").asDouble(), is(100.0));
        assertThat(suggest("{\"search\": \"bud\", \"suggesterName\": \"sg\"}").has("@search.coverage"), is(false));
    }

    @Test
    void autocompleteCompletesTheLastWordInEachMode() {
        ObjectNode mountain = autocomplete("{\"search\": \"moun\", \"suggesterName\": \"sg\"}");
        assertThat(mountain.get("value").toString(), is("[{\"text\":\"mountain\",\"queryPlusText\":\"mountain\"}]"));
        ObjectNode inn = autocomplete("{\"search\": \"budget i\", \"suggesterName\": \"sg\", "
            + "\"autocompleteMode\": \"oneTerm\"}");
        assertThat(inn.get("value").toString(), is("[{\"text\":\"inn\",\"queryPlusText\":\"budget inn\"}]"));
        assertThat(values(autocomplete("{\"search\": \"moun\", \"suggesterName\": \"sg\", \"autocompleteMode\": "
            + "\"twoTerms\"}"), "text"), contains("mountain lodge"));
        ObjectNode context = autocomplete("{\"search\": \"harbor b\", \"suggesterName\": \"sg\", "
            + "\"autocompleteMode\": \"oneTermWithContext\"}");
        assertThat(context.get("value").toString(),
            is("[{\"text\":\"harbor boutique\",\"queryPlusText\":\"harbor boutique\"}]"));
        assertThat(values(autocomplete("{\"search\": \"ocean b\", \"suggesterName\": \"sg\", \"autocompleteMode\": "
            + "\"oneTermWithContext\"}"), "text"), empty());
    }

    @Test
    void autocompleteRanksAndFiltersCompletions() {
        assertThat(values(autocomplete("{\"search\": \"b\", \"suggesterName\": \"sg\"}"), "text"),
            contains("boutique", "budget"));
        assertThat(values(autocomplete("{\"search\": \"b\", \"suggesterName\": \"sg\", \"top\": 1}"), "text"),
            contains("boutique"));
        assertThat(values(autocomplete("{\"search\": \"b\", \"suggesterName\": \"sg\", \"filter\": \"rating lt 3\"}"),
            "text"), contains("budget"));
        assertThat(values(autocomplete("{\"search\": \"montain\", \"suggesterName\": \"sg\", \"fuzzy\": true}"),
            "text"), contains("mountain"));
        assertThat(values(autocomplete("{\"search\": \"moun\", \"suggesterName\": \"sg\", \"highlightPreTag\": "
            + "\"<b>\", \"highlightPostTag\": \"</b>\"}"), "queryPlusText"), contains("<b>mountain</b>"));
    }

    @Test
    void invalidRequestsAreBadRequests() {
        for (String json : List.of(
                "{\"search\": \"bud\", \"suggesterName\": \"nope\"}",
                "{\"search\": \"bud\", \"suggesterName\": \"\"}",
                "{\"search\": \"bud\"}",
                "{\"suggesterName\": \"sg\"}",
                "{\"search\": \"\", \"suggesterName\": \"sg\"}",
                "{\"search\": \"" + "a".repeat(101) + "\", \"suggesterName\": \"sg\"}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"top\": 0}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"top\": 101}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"highlightPreTag\": \"<b>\"}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"searchFields\": \"description\"}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"minimumCoverage\": 101}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"fuzzy\": \"maybe\"}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"orderby\": \"description\"}",
                "{\"search\": \"bud\", \"suggesterName\": \"sg\", \"filter\": \"nope eq 1\"}")) {
            assertThrows(SearchException.class, () -> suggest(json), json);
        }
        assertThrows(SearchException.class, () -> autocomplete("{\"search\": \"bud\", \"suggesterName\": \"sg\", "
            + "\"autocompleteMode\": \"threeTerms\"}"));
    }
}
