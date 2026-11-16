package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorSearchValidatorTest {

    private static final String HNSW = "{\"name\":\"a\",\"kind\":\"hnsw\"}";
    private static final String SINGLE = field("Collection(Edm.Single)", 3, "");

    private static String index(String vectorField, String vectorSearch) {
        return "{\"name\":\"vectors\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true}," + vectorField
            + "],\"vectorSearch\":" + vectorSearch + "}";
    }

    private static String field(String type, int dimensions, String extra) {
        return "{\"name\":\"vec\",\"type\":\"" + type + "\",\"dimensions\":" + dimensions
            + ",\"vectorSearchProfile\":\"p\"" + extra + "}";
    }

    private static String profile(String algorithmJson, String profileExtra, String sections) {
        return "{\"algorithms\":[" + algorithmJson + "],\"profiles\":[{\"name\":\"p\",\"algorithm\":\"a\""
            + profileExtra + "}]" + sections + "}";
    }

    private static JsonNode vectorSearch(String definition) {
        return EngineFixtures.definition(definition).json().get("vectorSearch");
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void documentedDefaultsAreFilledIn() {
        JsonNode section = vectorSearch(index(SINGLE, profile(HNSW + ",{\"name\":\"e\",\"kind\":\"exhaustiveKnn\"}",
            ",\"compression\":\"sq\",\"vectorizer\":\"oai\"",
            ",\"compressions\":[{\"name\":\"sq\",\"kind\":\"scalarQuantization\",\"truncationDimension\":2,"
                + "\"rescoringOptions\":{\"defaultOversampling\":10}}],"
                + "\"vectorizers\":[{\"name\":\"oai\",\"kind\":\"azureOpenAI\",\"azureOpenAIParameters\":{"
                + "\"resourceUri\":\"https://x.openai.azure.com/\",\"deploymentId\":\"d\"}}]")));
        assertThat(section.at("/algorithms/0/hnswParameters").toString(),
            is("{\"metric\":\"cosine\",\"m\":4,\"efConstruction\":400,\"efSearch\":500}"));
        assertThat(section.at("/algorithms/1/exhaustiveKnnParameters").toString(), is("{\"metric\":\"cosine\"}"));
        assertThat(section.at("/compressions/0/rescoringOptions").toString(), is("{\"enableRescoring\":true,"
            + "\"defaultOversampling\":10.0,\"rescoreStorageMethod\":\"preserveOriginals\"}"));
        assertThat(section.at("/vectorizers/0/azureOpenAIParameters/resourceUri").asText(),
            is("https://x.openai.azure.com"));
        assertThat(propertyNames(section), is(List.of("algorithms", "profiles", "vectorizers", "compressions")));
        assertThat(vectorSearch(index(SINGLE, section.toString())), is(section));
    }

    @Test
    void absentSectionsAreEmptyAndOptionalProfilePartsStayAbsent() {
        JsonNode section = vectorSearch(index(SINGLE, profile(HNSW, "", "")));
        assertThat(section.get("vectorizers").size(), is(0));
        assertThat(section.get("compressions").size(), is(0));
        assertThat(section.at("/profiles/0").has("vectorizer"), is(false));
    }

    @Test
    void invalidConfigurationsAreRejected() {
        String[][] cases = {
            {profile("{\"name\":\"a\",\"kind\":\"hnsw\",\"hnswParameters\":{\"m\":11}}", "", ""), "between 4 and 10"},
            {profile("{\"name\":\"a\",\"kind\":\"hnsw\",\"hnswParameters\":{\"efSearch\":99}}", "", ""),
                "between 100 and 1000"},
            {profile("{\"name\":\"a\",\"kind\":\"hnsw\",\"hnswParameters\":{\"metric\":\"manhattan\"}}", "", ""),
                "invalid metric 'manhattan'"},
            {profile("{\"name\":\"a\",\"kind\":\"ivf\"}", "", ""), "invalid kind 'ivf'"},
            {profile(HNSW + "," + HNSW, "", ""), "used more than once"},
            {profile(HNSW, ",\"vectorizer\":\"none\"", ""), "names the vectorizer 'none'"},
            {profile(HNSW, ",\"compression\":\"none\"", ""), "names the compression 'none'"},
            {profile(HNSW, "", ",\"compressions\":[{\"name\":\"c\",\"kind\":\"productQuantization\"}]"),
                "invalid kind 'productQuantization'"},
            {profile(HNSW, "", ",\"compressions\":[{\"name\":\"c\",\"kind\":\"scalarQuantization\","
                + "\"scalarQuantizationParameters\":{\"quantizedDataType\":\"int4\"}}]"), "must be 'int8'"},
            {profile(HNSW, "", ",\"compressions\":[{\"name\":\"c\",\"kind\":\"binaryQuantization\","
                + "\"rescoringOptions\":{\"defaultOversampling\":0.5}}]"), "at least 1"},
            {profile(HNSW, "", ",\"compressions\":[{\"name\":\"c\",\"kind\":\"binaryQuantization\","
                + "\"rescoringOptions\":{\"enableRescoring\":false,\"defaultOversampling\":4}}]"),
                "only be set when 'enableRescoring' is true"},
            {profile(HNSW, "", ",\"compressions\":[{\"name\":\"c\",\"kind\":\"binaryQuantization\","
                + "\"truncationDimension\":0}]"), "positive integer"},
            {profile(HNSW, "", ",\"vectorizers\":[{\"name\":\"z\",\"kind\":\"azureOpenAI\"}]"),
                "must set 'azureOpenAIParameters'"},
            {profile(HNSW, "", ",\"vectorizers\":[{\"name\":\"z\",\"kind\":\"azureOpenAI\",\"azureOpenAIParameters\":{"
                + "\"resourceUri\":\"https://x.openai.azure.com\",\"deploymentId\":\"d\",\"modelName\":\"gpt-9\"}}]"),
                "names the model 'gpt-9'"},
            {profile(HNSW, ",\"vectorizer\":\"z\"", ",\"vectorizers\":[{\"name\":\"z\",\"kind\":\"azureOpenAI\","
                + "\"azureOpenAIParameters\":{\"resourceUri\":\"https://x.openai.azure.com\",\"deploymentId\":\"d\","
                + "\"modelName\":\"text-embedding-ada-002\"}}]"), "produces 1536"},
            {profile(HNSW, "", ",\"vectorizers\":[{\"name\":\"z\",\"kind\":\"customWebApi\",\"customWebApiParameters\":{"
                + "\"uri\":\"https://x.example\",\"httpMethod\":\"GET\"}}]"), "'POST' or 'PUT'"},
            {profile(HNSW, "", ",\"vectorizers\":[{\"name\":\"z\",\"kind\":\"customWebApi\",\"customWebApiParameters\":{"
                + "\"uri\":\"https://x.example\",\"httpHeaders\":{\"Host\":\"h\"}}}]"), "cannot set the HTTP header"},
            {profile(HNSW, "", ",\"vectorizers\":[{\"name\":\"z\",\"kind\":\"customWebApi\",\"customWebApiParameters\":{"
                + "\"uri\":\"https://x.example\",\"timeout\":\"PT300S\"}}]"), "between 1 and 230 seconds"},
            {profile("{\"name\":\"a\",\"kind\":\"hnsw\",\"hnswParameters\":{\"metric\":\"hamming\"}}", "", ""),
                "only valid for Collection(Edm.Byte) fields with vectorEncoding 'packedBit'"}
        };
        for (String[] invalid : cases) {
            SearchException error = assertThrows(SearchException.class,
                () -> EngineFixtures.definition(index(SINGLE, invalid[0])), invalid[1]);
            assertThat(error.getMessage(), containsString(invalid[1]));
            assertThat(error.status(), is(400));
        }
    }

    @Test
    void binaryFieldsAndCompressionMatchTheirElementTypes() {
        String packed = field("Collection(Edm.Byte)", 16, ",\"vectorEncoding\":\"packedBit\"");
        EngineFixtures.definition(index(packed,
            profile("{\"name\":\"a\",\"kind\":\"exhaustiveKnn\",\"exhaustiveKnnParameters\":{\"metric\":\"hamming\"}}",
                "", "")));
        assertThat(assertThrows(SearchException.class, () -> EngineFixtures.definition(index(packed,
            profile(HNSW, "", "")))).getMessage(), containsString("needs a vector search algorithm with the "
            + "'hamming' metric"));
        assertThat(assertThrows(SearchException.class, () -> EngineFixtures.definition(index(
            field("Collection(Edm.SByte)", 3, ""), profile(HNSW, ",\"compression\":\"c\"",
                ",\"compressions\":[{\"name\":\"c\",\"kind\":\"scalarQuantization\"}]")))).getMessage(),
            containsString("quantization applies to Collection(Edm.Single) and Collection(Edm.Half) fields"));
    }

    @Test
    void updatesKeepStorageDecisions() {
        String compression = ",\"compressions\":[{\"name\":\"c\",\"kind\":\"binaryQuantization\","
            + "\"rescoringOptions\":{\"rescoreStorageMethod\":\"preserveOriginals\"}}]";
        SearchIndexDefinition existing = EngineFixtures.definition(index(SINGLE, profile(HNSW, "", compression)));
        SearchIndexDefinition unstored = EngineFixtures.definition(index(SINGLE + ",{\"name\":\"more\","
            + "\"type\":\"Collection(Edm.Single)\",\"dimensions\":3,\"vectorSearchProfile\":\"p\",\"stored\":false}",
            profile(HNSW, "", compression)));
        assertThat(assertThrows(SearchException.class, () -> IndexUpdateValidator.validate(existing, unstored, false))
            .getMessage(), containsString("'stored' can only be set to false on vector fields created with the index"));
        SearchIndexDefinition discarded = EngineFixtures.definition(index(SINGLE, profile(HNSW, "",
            compression.replace("preserveOriginals", "discardOriginals"))));
        assertThat(assertThrows(SearchException.class, () -> IndexUpdateValidator.validate(existing, discarded, false))
            .getMessage(), containsString("'rescoreStorageMethod' of the existing compression 'c' cannot be changed"));
        IndexUpdateValidator.validate(existing, existing, false);
    }
}
