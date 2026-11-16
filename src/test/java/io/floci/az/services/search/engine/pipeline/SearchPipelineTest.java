package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class SearchPipelineTest {

    private static final String DEFINITION = """
        {"name": "items", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "text", "type": "Edm.String"},
          {"name": "rank", "type": "Edm.Int32"}
        ]}
        """;

    private static final SearchPipeline PIPELINE = SearchPipeline.standard();

    private static LiveIndex items(int count) {
        List<String> documents = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            documents.add("{\"id\":\"" + String.format("%03d", index) + "\",\"text\":\"item\",\"rank\":" + index + "}");
        }
        return EngineFixtures.index(DEFINITION, documents.toArray(String[]::new));
    }

    private static SearchOutcome run(LiveIndex index, SearchRequest request) {
        return PIPELINE.run(index, request, EngineSettings.DEFAULTS);
    }

    private static List<String> keys(SearchOutcome outcome) {
        return outcome.results().stream().map(result -> result.document().get("id").asText()).toList();
    }

    @Test
    void withoutTopResponsesHoldFiftyAndContinue() {
        LiveIndex index = items(120);
        SearchRequest request = SearchRequest.builder().search("item").count(true).build();
        SearchOutcome first = run(index, request);
        assertThat(first.results(), hasSize(50));
        assertThat(first.count(), is(120L));
        assertThat(first.nextPage().skip(), is(50));
        assertThat(first.nextPage().top(), nullValue());
        SearchOutcome second = run(index, first.nextPage());
        assertThat(second.results(), hasSize(50));
        assertThat(second.nextPage().skip(), is(100));
        SearchOutcome third = run(index, second.nextPage());
        assertThat(third.results(), hasSize(20));
        assertThat(third.nextPage(), nullValue());
    }

    @Test
    void anExplicitTopDoesNotContinue() {
        LiveIndex index = items(120);
        SearchOutcome outcome = run(index, SearchRequest.builder().top(30).skip(100).build());
        assertThat(outcome.results(), hasSize(20));
        assertThat(outcome.nextPage(), nullValue());
        assertThat(outcome.count(), nullValue());
    }

    @Test
    void orderByThenTieBreakingIsDeterministic() {
        LiveIndex index = items(5);
        assertThat(keys(run(index, SearchRequest.builder().orderBy("rank desc").build())),
            contains("004", "003", "002", "001", "000"));
        assertThat(keys(run(index, SearchRequest.builder().build())), contains("000", "001", "002", "003", "004"));
    }

    @Test
    void filtersAndTextCombine() {
        LiveIndex index = items(10);
        SearchOutcome outcome = run(index, SearchRequest.builder().search("item").filter("rank ge 8")
            .orderBy("rank").count(true).build());
        assertThat(keys(outcome), contains("008", "009"));
        assertThat(outcome.count(), is(2L));
    }

    @Test
    void theRequestEchoesOnlyWhatWasSet() {
        SearchRequest request = SearchRequest.builder().search("x").top(5).count(true).build();
        assertThat(request.withPaging(10, 5).toJson().toString(), is("{\"count\":true,\"search\":\"x\","
            + "\"skip\":10,\"top\":5}"));
    }
}
