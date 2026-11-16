package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.analysis.AnalyzerRegistry;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorizerSecretsTest {

    private static final String DEFINITION = """
        {"name": "secrets", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "v", "type": "Collection(Edm.Single)", "dimensions": 3, "vectorSearchProfile": "p"}
        ],
        "vectorSearch": {
          "algorithms": [{"name": "a", "kind": "hnsw"}],
          "profiles": [{"name": "p", "algorithm": "a", "vectorizer": "custom"}],
          "vectorizers": [
            {"name": "openai", "kind": "azureOpenAI", "azureOpenAIParameters": {
              "resourceUri": "https://x.openai.azure.com", "deploymentId": "d", "apiKey": "key-1"}},
            {"name": "custom", "kind": "customWebApi", "customWebApiParameters": {
              "uri": "https://embed.example/api?code=fn-key&mode=fast", "httpHeaders": {"x-api-key": "header-1"}}},
            {"name": "compatible", "kind": "openAICompatible", "openAICompatibleParameters": {
              "uri": "https://models.example/v1/embeddings", "modelName": "text-embedding-3-small",
              "headers": {"Authorization": "Bearer token-1"}}}
          ]
        }}
        """;

    private static JsonNode vectorizer(JsonNode index, int position) {
        return index.at("/vectorSearch/vectorizers/" + position);
    }

    @Test
    void getRedactsKeysHeadersAndUriSecrets() {
        SearchIndexDefinition definition = EngineFixtures.definition(DEFINITION);
        ObjectNode redacted = VectorizerSecrets.redact(definition.json());
        assertThat(vectorizer(redacted, 0).at("/azureOpenAIParameters/apiKey").asText(), is("<redacted>"));
        assertThat(vectorizer(redacted, 1).at("/customWebApiParameters/httpHeaders/x-api-key").asText(),
            is("<redacted>"));
        assertThat(vectorizer(redacted, 1).at("/customWebApiParameters/uri").asText(),
            is("https://embed.example/api?code=<redacted>&mode=fast"));
        assertThat(vectorizer(redacted, 2).at("/openAICompatibleParameters").has("headers"), is(false));
        assertThat(vectorizer(definition.json(), 0).at("/azureOpenAIParameters/apiKey").asText(), is("key-1"));
    }

    @Test
    void anUpdateSendingThePlaceholdersBackKeepsTheStoredSecrets() {
        SearchIndexDefinition existing = EngineFixtures.definition(DEFINITION);
        ObjectNode resent = VectorizerSecrets.redact(existing.json());
        JsonNode restored = VectorizerSecrets.restore(resent, existing);
        SearchIndexDefinition updated = IndexDefinitionParser.parse(restored, "secrets",
            AnalyzerRegistry.defaultRegistry());
        assertThat(updated.json().get("vectorSearch"), is(existing.json().get("vectorSearch")));
    }

    @Test
    void placeholdersWithoutAStoredSecretAreRefused() {
        SearchIndexDefinition existing = EngineFixtures.definition(DEFINITION);
        ObjectNode moved = VectorizerSecrets.redact(existing.json());
        ((ObjectNode) vectorizer(moved, 0).get("azureOpenAIParameters")).put("resourceUri", "https://y.openai.azure.com");
        SearchException moveError = assertThrows(SearchException.class, () -> IndexDefinitionParser.parse(
            VectorizerSecrets.restore(moved, existing), "secrets",
            AnalyzerRegistry.defaultRegistry()));
        assertThat(moveError.getMessage(), containsString("'apiKey'"));
        SearchException createError = assertThrows(SearchException.class, () -> EngineFixtures.definition(
            DEFINITION.replace("header-1", "<redacted>")));
        assertThat(createError.status(), is(400));
        assertThat(createError.getMessage(), containsString("httpHeaders.x-api-key"));
    }
}
