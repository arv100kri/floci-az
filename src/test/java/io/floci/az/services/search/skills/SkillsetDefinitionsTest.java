package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.skills.SkillsetDefinitions.ParsedSkillset;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillsetDefinitionsTest {

    private static ObjectNode json(String text) {
        return (ObjectNode) SearchJson.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ParsedSkillset parse(String body, ObjectNode previous, boolean httpsOnly) {
        return SkillsetDefinitions.parse(json(body), "s", previous, name -> Optional.empty(), httpsOnly);
    }

    private static String webApi(String uri) {
        return "{\"name\": \"s\", \"skills\": [{\"@odata.type\": \"#Microsoft.Skills.Custom.WebApiSkill\", "
            + "\"name\": \"w\", \"uri\": \"" + uri + "\", \"inputs\": [{\"name\": \"t\", \"source\": "
            + "\"/document/t\"}], \"outputs\": [{\"name\": \"o\"}]}]}";
    }

    @Test
    void plainHttpIsRefusedExceptForLocalEndpointsOutsideStrictParity() {
        SearchException refused = assertThrows(SearchException.class,
            () -> parse(webApi("http://example.invalid/api/label"), null, false));
        assertThat(refused.getMessage(), equalTo("One or more skills are invalid. Details: Error in skill 'w': 'uri' "
            + "must use https: only the https URI scheme is allowed."));
        assertThrows(SearchException.class, () -> parse(webApi("http://8.8.8.8/api"), null, false));
        for (String local : new String[] {"http://localhost:7071/api/skill", "http://skills.localhost/api",
            "http://127.0.0.1:8080/x", "http://[::1]:8080/x", "http://10.1.2.3/x", "http://192.168.1.20/x",
            "http://host.docker.internal:7071/x", "http://skill-api:8080/x"}) {
            assertThat(local, parse(webApi(local), null, false).stored().at("/skills/0/uri").asText(),
                equalTo(local));
            assertThrows(SearchException.class, () -> parse(webApi(local), null, true), local);
        }
        parse(webApi("https://fn.example.net/api/skill"), null, true);
        String aml = "{\"name\": \"s\", \"skills\": [{\"@odata.type\": \"#Microsoft.Skills.Custom.AmlSkill\", "
            + "\"uri\": \"%s\", \"inputs\": [], \"outputs\": []}]}";
        parse(aml.formatted("http://localhost:5001/score"), null, false);
        assertThrows(SearchException.class, () -> parse(aml.formatted("http://ml.example.com/score"), null, false));
        assertThrows(SearchException.class, () -> parse(aml.formatted("http://localhost:5001/score"), null, true));
    }

    @Test
    void secretQueryParametersAreMatchedByNameIgnoringCaseAndEncodingAndByOccurrence() {
        String uri = "https://fn.example.net/api?Code=one&x=1&%63ode=two&sig=abc";
        assertThat(WebApiSecrets.redactUri(uri), equalTo("https://fn.example.net/api?Code=<redacted>&x=1"
            + "&%63ode=<redacted>&sig=<redacted>"));
        ObjectNode stored = parse(webApi(uri), null, false).stored();
        ObjectNode kept = parse(webApi("https://fn.example.net/api?Code=<redacted>&x=2&%63ode=%3Cunchanged%3E"
            + "&sig=new"), stored, false).stored();
        assertThat(kept.at("/skills/0/uri").asText(), equalTo("https://fn.example.net/api?Code=one&x=2&%63ode=two"
            + "&sig=new"));
        SearchException sigRebound = assertThrows(SearchException.class, () -> parse(webApi(
            "https://fn.example.net/api?Code=one&x=2&%63ode=two&sig=<redacted>"), stored, false));
        assertThat(sigRebound.getMessage(), containsString("whose other query parameters changed"));
        ObjectNode sameQuery = parse(webApi("https://fn.example.net/api?Code=<redacted>&x=1&%63ode=<redacted>"
            + "&sig=<redacted>"), stored, false).stored();
        assertThat(sameQuery.at("/skills/0/uri").asText(), equalTo(uri));
    }

    @Test
    void embeddingDimensionsAreCheckedAgainstTheModel() {
        String embedding = "{\"name\": \"s\", \"skills\": [{\"@odata.type\": "
            + "\"#Microsoft.Skills.Text.AzureOpenAIEmbeddingSkill\", \"resourceUri\": \"https://a.openai.azure.com\", "
            + "\"deploymentId\": \"d\", \"modelName\": \"%s\", \"dimensions\": %d, "
            + "\"inputs\": [{\"name\": \"text\", \"source\": \"/document/c\"}], "
            + "\"outputs\": [{\"name\": \"embedding\"}]}]}";
        parse(embedding.formatted("text-embedding-3-small", 256), null, false);
        SearchException tooMany = assertThrows(SearchException.class,
            () -> parse(embedding.formatted("text-embedding-ada-002", 256), null, false));
        assertThat(tooMany.getMessage(), containsString("'dimensions' 256 is not supported by "
            + "'text-embedding-ada-002', which supports 1536 to 1536."));
        assertThrows(SearchException.class, () -> parse(embedding.formatted("gpt-4o", 3), null, false));
    }
}
