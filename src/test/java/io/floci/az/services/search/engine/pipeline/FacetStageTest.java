package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.floci.az.services.search.engine.HotelsCorpus;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FacetStageTest {

    private static final LiveIndex HOTELS = HotelsCorpus.index();

    private static ArrayNode facet(String expression) {
        return facet(SearchRequest.builder().facets(List.of(expression)).build(), expression);
    }

    private static ArrayNode facet(SearchRequest request, String expression) {
        String name = expression.split(",")[0].trim();
        return HotelsCorpus.run(HOTELS, request).facets().get(name);
    }

    @Test
    void valueFacetsCountMatchingDocumentsMostFrequentFirst() {
        assertThat(facet("category").toString(), is("[{\"count\":2,\"value\":\"Budget\"},{\"count\":2,\"value\":"
            + "\"Resort\"},{\"count\":1,\"value\":\"Boutique\"},{\"count\":1,\"value\":\"Luxury\"}]"));
        assertThat(facet("category,count:10,sort:value").toString(), is("[{\"count\":1,\"value\":\"Boutique\"},"
            + "{\"count\":2,\"value\":\"Budget\"},{\"count\":1,\"value\":\"Luxury\"},{\"count\":2,\"value\":"
            + "\"Resort\"}]"));
        assertThat(facet("category,sort:-count").toString(), is("[{\"count\":1,\"value\":\"Boutique\"},{\"count\":1,"
            + "\"value\":\"Luxury\"},{\"count\":2,\"value\":\"Budget\"},{\"count\":2,\"value\":\"Resort\"}]"));
        assertThat(facet("rating,sort:-value").toString(), is("[{\"count\":1,\"value\":5},{\"count\":2,\"value\":4},"
            + "{\"count\":2,\"value\":3},{\"count\":1,\"value\":2}]"));
        assertThat(facet("parking").toString(), is("[{\"count\":3,\"value\":false},{\"count\":3,\"value\":true}]"));
    }

    @Test
    void collectionsCountEachDocumentOncePerValueAndCountLimitsTheBuckets() {
        assertThat(facet("tags").toString(), is("[{\"count\":3,\"value\":\"wifi\"},{\"count\":2,\"value\":\"pool\"},"
            + "{\"count\":2,\"value\":\"view\"},{\"count\":1,\"value\":\"boutique\"},{\"count\":1,\"value\":"
            + "\"fireplace\"},{\"count\":1,\"value\":\"gym\"},{\"count\":1,\"value\":\"hiking\"},{\"count\":1,"
            + "\"value\":\"parking\"},{\"count\":1,\"value\":\"shuttle\"}]"));
        assertThat(facet("tags,count:2").toString(), is("[{\"count\":3,\"value\":\"wifi\"},{\"count\":2,\"value\":"
            + "\"pool\"}]"));
        assertThat(facet("tags,count:0").size(), is(9));
    }

    @Test
    void valuesFacetsReturnEveryRangeEmptyOnesIncluded() {
        assertThat(facet("price,values:100|200").toString(), is("[{\"count\":2,\"to\":100.0},{\"count\":3,"
            + "\"from\":100.0,\"to\":200.0},{\"count\":1,\"from\":200.0}]"));
        assertThat(facet("rating,values:1 | 2|3").toString(), is("[{\"count\":0,\"to\":1},{\"count\":0,\"from\":1,"
            + "\"to\":2},{\"count\":1,\"from\":2,\"to\":3},{\"count\":5,\"from\":3}]"));
        assertThat(facet("renovated,values:2020-01-01T00:00:00Z").toString(), is("[{\"count\":2,\"to\":"
            + "\"2020-01-01T00:00:00Z\"},{\"count\":4,\"from\":\"2020-01-01T00:00:00Z\"}]"));
    }

    @Test
    void intervalFacetsBucketNumbersAndDates() {
        assertThat(facet("rating,interval:2").toString(), is("[{\"count\":3,\"value\":2},{\"count\":3,\"value\":4}]"));
        assertThat(facet("price,interval:100").toString(), is("[{\"count\":2,\"value\":0.0},{\"count\":3,\"value\":"
            + "100.0},{\"count\":1,\"value\":200.0}]"));
        assertThat(facet("renovated,interval:year").toString(), is("[{\"count\":1,\"value\":\"2010-01-01T00:00:00Z\"},"
            + "{\"count\":1,\"value\":\"2015-01-01T00:00:00Z\"},{\"count\":3,\"value\":\"2021-01-01T00:00:00Z\"},"
            + "{\"count\":1,\"value\":\"2023-01-01T00:00:00Z\"}]"));
        assertThat(facet("renovated,interval:quarter").get(2).toString(),
            is("{\"count\":1,\"value\":\"2021-01-01T00:00:00Z\"}"));
        assertThat(facet("renovated,interval:week").get(2).toString(),
            is("{\"count\":1,\"value\":\"2021-03-15T00:00:00Z\"}"));
        ArrayNode shifted = facet("renovated,interval:day,timeoffset:-01:00");
        assertThat(shifted.get(1).toString(), is("{\"count\":1,\"value\":\"2015-06-30T01:00:00Z\"}"));
        assertThat(shifted.get(4).toString(), is("{\"count\":1,\"value\":\"2021-12-31T01:00:00Z\"}"));
    }

    @Test
    void facetsCountOnlyWhatTheQueryAndFilterMatched() {
        assertThat(facet(SearchRequest.builder().filter("parking eq true").facets(List.of("category")).build(),
            "category").toString(), is("[{\"count\":2,\"value\":\"Resort\"},{\"count\":1,\"value\":\"Budget\"}]"));
        assertThat(facet(SearchRequest.builder().search("pool").facets(List.of("tags")).build(), "tags").toString(),
            is("[{\"count\":2,\"value\":\"pool\"},{\"count\":1,\"value\":\"gym\"},{\"count\":1,\"value\":\"view\"},"
                + "{\"count\":1,\"value\":\"wifi\"}]"));
    }

    @Test
    void invalidExpressionsAreBadRequests() {
        for (String expression : List.of("name", "nope", "location", "category,interval:2", "rating,count:2,values:1",
                "rating,interval:2,values:1", "rating,timeoffset:+01:00", "renovated,interval:fortnight",
                "rating,interval:0", "rating,foo:bar", "rating,count:-1", "rating,sort:up", "rating,values:1|x",
                "renovated,interval:day,timeoffset:1h", "", "rating,count")) {
            assertThrows(SearchException.class, () -> facet(expression), expression);
        }
    }
}
