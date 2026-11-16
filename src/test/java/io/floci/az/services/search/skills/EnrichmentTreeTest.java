package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.skills.EnrichedDocument.Located;
import io.floci.az.services.search.skills.EnrichedDocument.PathValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnrichmentTreeTest {

    private static final String COUNTRIES = """
        {"content": "Study of BMN 110",
         "countries": [
           {"name": "A", "states": [{"zipcodes": ["1", "2"]}, {"zipcodes": ["3"]}]},
           {"name": "B", "states": [{"zipcodes": ["4"]}]}],
         "entities": [{"text": "BMN", "offset": 9, "category": "Organization"}],
         "keyphrases": ["Study of BMN", "Syndrome", "Pediatric Patients"]}
        """;

    private static ObjectNode json(String text) {
        return (ObjectNode) SearchJson.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static EnrichedDocument tree() {
        return new EnrichedDocument(json(COUNTRIES));
    }

    private static JsonNode evaluate(String expression, EnrichedDocument document) {
        return InputExpression.parse(expression).evaluate(path -> document.evaluate(path).value());
    }

    @Test
    void pathsDecodeTheDocumentedEscapes() {
        EnrichmentPath path = EnrichmentPath.parse("/document/~0~1documents/readme~2#~2requirements/~2*~2/*/#");
        assertThat(path.segment(1).name(), equalTo("~/documents"));
        assertThat(path.segment(2).name(), equalTo("readme#requirements"));
        assertThat(path.segment(3).name(), equalTo("*"));
        assertThat(path.segment(4), equalTo(EnrichmentPath.Segment.STAR));
        assertThat(path.segment(5), equalTo(EnrichmentPath.Segment.HASH));
        assertThat(EnrichmentPath.parse("document/content").toString(), equalTo("/document/content"));
        assertThrows(IllegalArgumentException.class, () -> EnrichmentPath.parse("/doc/content"));
        assertThrows(IllegalArgumentException.class, () -> EnrichmentPath.parse("/document//content"));
    }

    @Test
    void contextsEnumerateAndInputsBindToTheContextNode() {
        EnrichedDocument document = tree();
        EnrichmentPath countryContext = EnrichmentPath.parse("/document/countries/*");
        EnrichmentPath zipcodes = EnrichmentPath.parse("/document/countries/*/states/*/zipcodes/*");
        List<Located> countries = document.instances(countryContext);
        assertThat(countries, hasSize(2));
        assertThat(document.evaluate(zipcodes, countries.get(0), countryContext).value().toString(),
            equalTo("[\"1\",\"2\",\"3\"]"));
        assertThat(document.evaluate(zipcodes, countries.get(1), countryContext).value().toString(),
            equalTo("[\"4\"]"));

        EnrichmentPath stateContext = EnrichmentPath.parse("/document/countries/*/states/*");
        List<Located> states = document.instances(stateContext);
        assertThat(states, hasSize(3));
        assertThat(document.evaluate(zipcodes, states.get(1), stateContext).value().toString(),
            equalTo("[\"3\"]"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/countries/*/name"), states.get(2), stateContext)
            .value().asText(), equalTo("B"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/content"), states.get(0), stateContext)
            .value().asText(), equalTo("Study of BMN 110"));
    }

    @Test
    void unboundStarsFlattenAndHashKeepsArrays() {
        EnrichedDocument document = tree();
        assertThat(document.evaluate(EnrichmentPath.parse("/document/countries/*/states/*/zipcodes/*")).value()
            .toString(), equalTo("[\"1\",\"2\",\"3\",\"4\"]"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/countries/*/states/*/zipcodes/#")).value()
            .toString(), equalTo("[[\"1\",\"2\"],[\"3\"],[\"4\"]]"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/keyphrases/1")).value().asText(),
            equalTo("Syndrome"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/entities/0/text")).value().asText(),
            equalTo("BMN"));
    }

    @Test
    void selectionsThatFailCarryTheDocumentedReason() {
        EnrichedDocument document = tree();
        PathValue missing = document.evaluate(EnrichmentPath.parse("/document/normalized_images/0/imageTags"));
        assertThat(missing.isMissing(), is(true));
        assertThat(missing.problem(), equalTo("Missing value /document/normalized_images."));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/keyphrases/7")).problem(),
            equalTo("Unable to select 7 in array /document/keyphrases of length 3."));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/content/0")).problem(),
            equalTo("Unable to select 0 in non-array /document/content."));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/content/*")).problem(),
            equalTo("Cannot iterate over non-array /document/content."));
    }

    @Test
    void annotationsAreAddressedIndividuallyAndNotPartOfTheirParentsValue() {
        EnrichedDocument document = tree();
        document.root().child("content").node().annotate("language", SearchJson.NODES.textNode("en"));
        Located secondPhrase = document.instances(EnrichmentPath.parse("/document/keyphrases/*")).get(1);
        secondPhrase.node().annotate("score", SearchJson.NODES.numberNode(0.5));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/content/language")).value().asText(),
            equalTo("en"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/content")).value().asText(),
            equalTo("Study of BMN 110"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/keyphrases/*/score")).value().toString(),
            equalTo("[0.5]"));
        assertThat(document.evaluate(EnrichmentPath.parse("/document/keyphrases")).value().toString(),
            equalTo("[\"Study of BMN\",\"Syndrome\",\"Pediatric Patients\"]"));
    }

    @Test
    void nullAndMissingContextsRunNothing() {
        EnrichedDocument document = new EnrichedDocument(json("{\"frenchSentences\": null, \"pages\": []}"));
        assertThat(document.instances(EnrichmentPath.parse("/document/frenchSentences")), hasSize(0));
        assertThat(document.instances(EnrichmentPath.parse("/document/pages/*")), hasSize(0));
        assertThat(document.instances(EnrichmentPath.parse("/document/nothing/*")), hasSize(0));
        assertThat(document.instances(EnrichmentPath.ROOT), hasSize(1));
    }

    @Test
    void expressionsFollowTheDocumentedExamples() {
        EnrichedDocument document = tree();
        assertThat(evaluate("=42", document).asInt(), is(42));
        assertThat(evaluate("=2.45E-4", document).asDouble(), is(0.000245));
        assertThat(evaluate("=\"unicod\\u0065\"", document).asText(), equalTo("unicode"));
        assertThat(evaluate("='some other string'", document).asText(), equalTo("some other string"));
        assertThat(evaluate("=[$(/document/entities/0/text), 'item']", document).toString(),
            equalTo("[\"BMN\",\"item\"]"));
        assertThat(evaluate("=[[$(/document/entities/0/text), 'item'],['item2', $(/document/keyphrases/1)]]",
            document).toString(), equalTo("[[\"BMN\",\"item\"],[\"item2\",\"Syndrome\"]]"));
        assertThat(evaluate("=!false", document).asBoolean(), is(true));
        assertThat(evaluate("=-$(/document/entities/0/offset)", document).asInt(), is(-9));
        assertThat(evaluate("=2+$(/document/entities/0/offset)", document).asInt(), is(11));
        assertThat(evaluate("=$(/document/entities/0/offset)-2", document).asInt(), is(7));
        assertThat(evaluate("=3/2", document).asDouble(), is(1.5));
        assertThat(evaluate("=$(/document/entities/0/offset)/3", document).toString(), equalTo("3"));
        assertThat(evaluate("=15%4", document).asInt(), is(3));
        assertThat(evaluate("=\"Hello,\" + \"world!\"", document).asText(), equalTo("Hello, world!"));
        assertThat(evaluate("=$(/document/entities/0/text) + $(/document/entities/0/category)", document).asText(),
            equalTo("BMN Organization"));
        assertThat(evaluate("=4<=4", document).asBoolean(), is(true));
        assertThat(evaluate("=15!=4", document).asBoolean(), is(true));
        assertThat(evaluate("=true^true", document).asBoolean(), is(false));
        assertThat(evaluate("=false||false", document).asBoolean(), is(false));
        assertThat(evaluate("=$(/document/entities/0/offset)==9?\"nine\":\"not nine\"", document).asText(),
            equalTo("nine"));
        assertThat(evaluate("=3*2+5", document).asInt(), is(11));
        assertThat(evaluate("=3*(2+5)", document).asInt(), is(21));
        assertThat(evaluate("= $(/document/language) == null", document).asBoolean(), is(true));
        assertThat(evaluate("= $(/document/language) == 'en' && $(/document/x) > 0.5", document).asBoolean(),
            is(false));
    }

    @Test
    void expressionsThatCannotBeParsedOrEvaluatedAreReported() {
        assertThrows(IllegalArgumentException.class, () -> InputExpression.parse("= 1 +"));
        assertThrows(IllegalArgumentException.class, () -> InputExpression.parse("= contract"));
        assertThrows(IllegalArgumentException.class, () -> InputExpression.parse("= 'open"));
        assertThrows(InputExpression.EvaluationException.class, () -> evaluate("= 'a' * 2", tree()));
        assertThrows(InputExpression.EvaluationException.class, () -> evaluate("= !'a'", tree()));
        assertThat(InputExpression.parse("= $(/document/a) == $(/document/b/*/c)").paths().toString(),
            equalTo("[/document/a, /document/b/*/c]"));
    }

    @Test
    void skillsRunAfterTheSkillsWhoseOutputsTheyRead() {
        SkillDefinition embed = skill(1, "{\"@odata.type\": \"#Microsoft.Skills.Text.AzureOpenAIEmbeddingSkill\", "
            + "\"name\": \"embed\", \"context\": \"/document/pages/*\", \"inputs\": [{\"name\": \"text\", "
            + "\"source\": \"/document/pages/*\"}], \"outputs\": [{\"name\": \"embedding\", \"targetName\": \"v\"}]}");
        SkillDefinition shape = skill(2, "{\"@odata.type\": \"#Microsoft.Skills.Util.ShaperSkill\", \"name\": \"shape\","
            + " \"inputs\": [{\"name\": \"x\", \"sourceContext\": \"/document/pages/*\", \"inputs\": [{\"name\": \"v\","
            + " \"source\": \"/document/pages/*/v\"}]}], \"outputs\": [{\"name\": \"output\"}]}");
        SkillDefinition split = skill(3, "{\"@odata.type\": \"#Microsoft.Skills.Text.SplitSkill\", \"name\": \"split\","
            + " \"inputs\": [{\"name\": \"text\", \"source\": \"/document/content\"}],"
            + " \"outputs\": [{\"name\": \"textItems\", \"targetName\": \"pages\"}]}");
        SkillDefinition language = skill(4, "{\"@odata.type\": \"#Microsoft.Skills.Util.ConditionalSkill\","
            + " \"name\": \"language\", \"inputs\": [{\"name\": \"condition\", \"source\": \"= $(/document/lang) == "
            + "null\"}], \"outputs\": [{\"name\": \"output\", \"targetName\": \"lang2\"}]}");
        List<String> order = SkillOrder.order(List.of(embed, shape, split, language)).stream()
            .map(SkillDefinition::name).toList();
        assertThat(order, contains("split", "embed", "shape", "language"));
        assertThat(SkillOrder.cycle(List.of(embed, shape, split, language)), hasSize(0));
    }

    @Test
    void unnamedSkillsAreNamedByPositionAndDefaultToTheDocumentContext() {
        SkillDefinition skill = skill(3, "{\"@odata.type\": \"#Microsoft.Skills.Util.ShaperSkill\","
            + " \"inputs\": [{\"name\": \"a\", \"source\": \"/document/a\"}], \"outputs\": [{\"name\": \"output\"}]}");
        assertThat(skill.name(), equalTo("#3"));
        assertThat(skill.context(), equalTo(EnrichmentPath.ROOT));
        assertThat(skill.outputs().get(0).targetName(), equalTo("output"));
        assertThat(skill.operationName(), equalTo("Enrichment.ShaperSkill.#3"));
        assertThat(skill.input("missing"), nullValue());
    }

    private static SkillDefinition skill(int position, String json) {
        return SkillDefinition.parse(json(json), position);
    }
}
