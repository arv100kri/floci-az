package io.floci.az.services.search.engine;

import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.engine.pipeline.SearchPipeline;
import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchRequest;

import java.util.List;

/**
 * The six-hotel corpus of the SDK compatibility suites ({@code SearchTestSupport.hotels()}), as an engine index, for
 * the query-feature unit tests: full syntax, facets, highlights, suggestions and scoring profiles.
 */
public final class HotelsCorpus {

    public static final String DEFINITION = """
        {"name": "hotels", "fields": [
          {"name": "id", "type": "Edm.String", "key": true, "searchable": false, "filterable": true, "sortable": true},
          {"name": "name", "type": "Edm.String", "searchable": true, "filterable": true, "sortable": true,
           "facetable": false},
          {"name": "description", "type": "Edm.String", "searchable": true, "filterable": false, "sortable": false,
           "facetable": false},
          {"name": "category", "type": "Edm.String", "searchable": false, "filterable": true, "sortable": true,
           "facetable": true},
          {"name": "tags", "type": "Collection(Edm.String)", "searchable": true, "filterable": true,
           "facetable": true},
          {"name": "rating", "type": "Edm.Int32", "filterable": true, "sortable": true, "facetable": true},
          {"name": "price", "type": "Edm.Double", "filterable": true, "sortable": true, "facetable": true},
          {"name": "parking", "type": "Edm.Boolean", "filterable": true, "facetable": true},
          {"name": "renovated", "type": "Edm.DateTimeOffset", "filterable": true, "sortable": true,
           "facetable": true},
          {"name": "location", "type": "Edm.GeographyPoint", "filterable": true, "sortable": true}
        ],
        "suggesters": [{"name": "sg", "searchMode": "analyzingInfixMatching", "sourceFields": ["name"]}]%s}
        """;

    public static final String[] DOCUMENTS = {
        hotel("1", "Ocean View Resort", "Luxury resort with a pool and an ocean view", "Resort",
            "[\"pool\", \"view\", \"wifi\"]", 5, 250.0, true, "2021-03-15T10:00:00Z", -122.33, 47.61),
        hotel("2", "Budget Inn", "Cheap motel near the airport with free parking", "Budget",
            "[\"parking\", \"wifi\"]", 2, 60.0, true, "2015-07-01T00:00:00Z", -122.30, 47.45),
        hotel("3", "City Center Hotel", "Modern hotel in the city center with a rooftop pool", "Luxury",
            "[\"pool\", \"gym\"]", 4, 180.0, false, "2023-01-20T08:30:00Z", -122.34, 47.60),
        hotel("4", "Mountain Lodge", "Rustic lodge with hiking trails and a stone fireplace", "Resort",
            "[\"hiking\", \"fireplace\"]", 3, 120.0, true, "2010-11-11T00:00:00Z", -105.27, 40.01),
        hotel("5", "Airport Suites", "Convenient suites next to the airport terminal", "Budget",
            "[\"wifi\", \"shuttle\"]", 3, 90.0, false, "2021-12-31T23:00:00Z", -122.31, 47.44),
        hotel("6", "Harbor Boutique", "Small boutique hotel on the harbor with a view", "Boutique",
            "[\"boutique\", \"view\"]", 4, 150.0, false, "2021-06-01T00:00:00Z", -122.40, 47.65)
    };

    private HotelsCorpus() {
    }

    /** The hotels index, with {@code extraSections} (",\"scoringProfiles\": [...]", or "") added to its definition. */
    public static LiveIndex index(String extraSections) {
        return EngineFixtures.index(DEFINITION.formatted(extraSections), DOCUMENTS);
    }

    public static LiveIndex index() {
        return index("");
    }

    /** Runs {@code request} through the standard pipeline. */
    public static SearchOutcome run(LiveIndex index, SearchRequest request) {
        return SearchPipeline.standard().run(index, request, EngineSettings.DEFAULTS);
    }

    /** The keys of the documents matching a full-syntax {@code query}, in result order. */
    public static List<String> lucene(LiveIndex index, String query) {
        return keys(run(index, SearchRequest.builder().search(query).queryType(QueryType.FULL).build()));
    }

    public static List<String> keys(SearchOutcome outcome) {
        return outcome.results().stream().map(result -> result.document().get("id").asText()).toList();
    }

    private static String hotel(String id, String name, String description, String category, String tags, int rating,
                                double price, boolean parking, String renovated, double longitude, double latitude) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"description\":\"" + description
            + "\",\"category\":\"" + category + "\",\"tags\":" + tags + ",\"rating\":" + rating + ",\"price\":" + price
            + ",\"parking\":" + parking + ",\"renovated\":\"" + renovated + "\",\"location\":{\"type\":\"Point\","
            + "\"coordinates\":[" + longitude + "," + latitude + "]}}";
    }
}
