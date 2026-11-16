package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Search data plane: index aliases")
class SearchAliasesTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BASE = "/alias-search";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static void createIndex(String name, String id) {
        admin().body("{\"name\": \"" + name + "\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"text\", \"type\": \"Edm.String\", \"searchable\": true}]}")
            .post(BASE + "/indexes" + API).then().statusCode(201);
        admin().body("{\"value\": [{\"@search.action\": \"upload\", \"id\": \"" + id + "\", \"text\": \"hello\"}]}")
            .post(BASE + "/indexes('" + name + "')/docs/search.index" + API).then().statusCode(200);
    }

    @Test
    void createGetListAndDeleteFollowTheRestContract() {
        createIndex("v1", "a");
        admin().body("{\"name\": \"live\", \"indexes\": [\"v1\"]}").post(BASE + "/aliases" + API).then()
            .statusCode(201)
            .header("ETag", startsWith("\"0x"))
            .header("Location", endsWith("/alias-search/aliases('live')?api-version=2024-07-01"))
            .body("'@odata.context'", endsWith("$metadata#aliases/$entity"))
            .body("name", equalTo("live"))
            .body("indexes", contains("v1"));
        admin().get(BASE + "/aliases('live')" + API).then().statusCode(200).body("indexes", contains("v1"));
        admin().get(BASE + "/aliases" + API).then().statusCode(200).body("value.name", contains("live"));
        admin().get(BASE + "/aliases?api-version=2024-07-01&$select=name").then().statusCode(200)
            .body("'@odata.context'", endsWith("$metadata#aliases(name)"));
        admin().body("{\"name\": \"live\", \"indexes\": [\"v1\"]}").post(BASE + "/aliases" + API).then()
            .statusCode(409);
        admin().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.aliasesCount.usage", equalTo(1));
        admin().delete(BASE + "/aliases('live')" + API).then().statusCode(204);
        admin().get(BASE + "/aliases('live')" + API).then().statusCode(404);
        admin().delete(BASE + "/aliases('live')" + API).then().statusCode(404);
    }

    @Test
    void anAliasMapsToExactlyOneExistingIndex() {
        createIndex("v1", "a");
        createIndex("v2", "b");
        String[] invalid = {
            "{\"name\": \"live\", \"indexes\": [\"v1\", \"v2\"]}",
            "{\"name\": \"live\", \"indexes\": []}",
            "{\"name\": \"live\"}",
            "{\"name\": \"live\", \"indexes\": [\"missing\"]}",
            "{\"name\": \"Live\", \"indexes\": [\"v1\"]}"
        };
        for (String body : invalid) {
            admin().body(body).post(BASE + "/aliases" + API).then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestParameter"));
        }
    }

    @Test
    void documentOperationsAndIndexReadsGoThroughTheAlias() {
        createIndex("v1", "a");
        createIndex("v2", "b");
        admin().body("{\"name\": \"live\", \"indexes\": [\"v1\"]}").put(BASE + "/aliases('live')" + API).then()
            .statusCode(201);
        admin().body("{\"search\": \"*\"}").post(BASE + "/indexes('live')/docs/search.post.search" + API).then()
            .statusCode(200).body("value.id", contains("a"));
        admin().body("{\"value\": [{\"@search.action\": \"upload\", \"id\": \"via-alias\", \"text\": \"hi\"}]}")
            .post(BASE + "/indexes('live')/docs/search.index" + API).then().statusCode(200);
        admin().get(BASE + "/indexes('v1')/docs('via-alias')" + API).then().statusCode(200);
        admin().get(BASE + "/indexes('live')/docs('a')" + API).then().statusCode(200).body("id", equalTo("a"));
        admin().get(BASE + "/indexes('live')/docs/$count" + API).then().statusCode(200).body(equalTo("2"));
        admin().get(BASE + "/indexes('live')/docs?api-version=2024-07-01&search=hello").then().statusCode(200)
            .body("value.id", contains("a"));
        admin().get(BASE + "/indexes('live')" + API).then().statusCode(200).body("name", equalTo("v1"));

        admin().body("{\"name\": \"live\", \"indexes\": [\"v2\"]}").put(BASE + "/aliases('live')" + API).then()
            .statusCode(204);
        admin().body("{\"search\": \"*\"}").post(BASE + "/indexes('live')/docs/search.post.search" + API).then()
            .statusCode(200).body("value.id", contains("b"));
    }

    @Test
    void aliasesCannotDeleteOrAnalyzeAndProtectTheirIndex() {
        createIndex("v1", "a");
        admin().body("{\"name\": \"live\", \"indexes\": [\"v1\"]}").post(BASE + "/aliases" + API).then()
            .statusCode(201);
        admin().delete(BASE + "/indexes('v1')" + API).then().statusCode(400)
            .body("error.message", containsString("live"));
        admin().delete(BASE + "/indexes('live')" + API).then().statusCode(404);
        admin().body("{\"text\": \"x\", \"analyzer\": \"keyword\"}")
            .post(BASE + "/indexes('live')/search.analyze" + API).then().statusCode(404);
        admin().delete(BASE + "/aliases('live')" + API).then().statusCode(204);
        admin().body("{\"search\": \"*\"}").post(BASE + "/indexes('live')/docs/search.post.search" + API).then()
            .statusCode(404);
        admin().delete(BASE + "/indexes('v1')" + API).then().statusCode(204);
    }
}
