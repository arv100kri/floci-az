package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.indexer.SkillsetRunner.EnrichmentResult;
import io.floci.az.services.search.skills.EnrichedDocument.Located;
import io.floci.az.services.search.skills.EnrichedDocument.PathValue;
import io.floci.az.services.search.skills.SkillDefinition.InputEntry;
import io.floci.az.services.search.skills.SkillDefinition.OutputEntry;
import io.floci.az.services.search.skills.SkillExecutor.RequiredInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The AI enrichment pipeline for one document: builds the enrichment tree from the indexer's {@code /document} node,
 * runs the skills in dependency order (each once per node of its context, with its inputs evaluated there and its
 * outputs added beneath that node under their {@code targetName}), then applies the output field mappings and the
 * index projections. A skill whose required input is missing or of the wrong type is skipped for that node with the
 * documented warning; a skill that fails, or that the emulator cannot run, is an error, and a document with errors
 * gets no index fields or projections. Plain Java with no CDI dependency: {@link EnrichmentSkillsetRunner} wires it.
 */
final class EnrichmentPipeline {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Map<String, SkillExecutor> executors;
    private final SkillExecutor notEmulated = new NotEmulatedSkill();
    private final BiFunction<String, String, String> keyFields;

    /**
     * @param http          the HTTP client of the skills that call an endpoint
     * @param modelEndpoint the OpenAI-compatible endpoint embedding skills call, or null when none is configured
     * @param keyFields     given a service and an index name, the name of the index's key field, or null when the
     *                      index does not exist
     */
    EnrichmentPipeline(SkillHttp http, String modelEndpoint, BiFunction<String, String, String> keyFields) {
        this.executors = Map.of(
            SkillTypes.SPLIT, new SplitSkill(),
            SkillTypes.MERGE, new MergeSkill(),
            SkillTypes.SHAPER, new UtilitySkills.Shaper(),
            SkillTypes.CONDITIONAL, new UtilitySkills.Conditional(),
            SkillTypes.DOCUMENT_EXTRACTION, new DocumentExtractionSkill(http),
            SkillTypes.WEB_API, new WebApiSkill(http),
            SkillTypes.AZURE_OPENAI_EMBEDDING, new AzureOpenAIEmbeddingSkill(http, modelEndpoint),
            SkillTypes.AML, new AmlSkill(http));
        this.keyFields = keyFields;
    }

    EnrichmentResult run(String service, ObjectNode skillset, ObjectNode document, ArrayNode outputFieldMappings) {
        SkillMessages messages = new SkillMessages();
        List<SkillDefinition> skills = parseSkills(skillset, messages);
        EnrichedDocument tree = new EnrichedDocument(document);
        if (skills != null) {
            for (SkillDefinition skill : SkillOrder.order(skills)) {
                runSkill(skill, tree, messages);
            }
        }
        if (messages.hasErrors()) {
            return new EnrichmentResult(NODES.objectNode(), List.of(), false, messages.warnings(), messages.errors());
        }
        ObjectNode indexFields = OutputFieldMappings.apply(tree, outputFieldMappings, messages);
        IndexProjector.Projection projection = IndexProjector.project(tree,
            skillset == null ? null : skillset.get("indexProjections"), document,
            index -> keyFields.apply(service, index), messages);
        if (messages.hasErrors()) {
            return new EnrichmentResult(NODES.objectNode(), List.of(), false, messages.warnings(), messages.errors());
        }
        return new EnrichmentResult(indexFields, projection.documents(), projection.skipParent(),
            messages.warnings(), messages.errors());
    }

    private static List<SkillDefinition> parseSkills(ObjectNode skillset, SkillMessages messages) {
        List<SkillDefinition> skills = new ArrayList<>();
        JsonNode array = skillset == null ? null : skillset.get("skills");
        if (array == null || array.isNull()) {
            return skills;
        }
        if (!(array instanceof ArrayNode items)) {
            messages.error("Enrichment.Skillset", "The skillset is invalid: 'skills' is not an array.");
            return null;
        }
        for (int index = 0; index < items.size(); index++) {
            if (!(items.get(index) instanceof ObjectNode skill)) {
                messages.error("Enrichment.Skillset", "The skillset is invalid: skill #" + (index + 1)
                    + " is not a JSON object.");
                return null;
            }
            try {
                skills.add(SkillDefinition.parse(skill, index + 1));
            } catch (IllegalArgumentException e) {
                messages.error("Enrichment.Skillset", "The skillset is invalid: skill #" + (index + 1) + ": "
                    + e.getMessage() + ".");
                return null;
            }
        }
        return skills;
    }

    private void runSkill(SkillDefinition skill, EnrichedDocument tree, SkillMessages messages) {
        List<Located> instances = tree.instances(skill.context());
        if (instances.isEmpty()) {
            return;
        }
        SkillExecutor executor = executors.getOrDefault(skill.odataType(), notEmulated);
        InputEvaluator evaluator = new InputEvaluator(tree);
        List<ObjectNode> records = new ArrayList<>(instances.size());
        List<Located> recordInstances = new ArrayList<>(instances.size());
        for (Located instance : instances) {
            ObjectNode record = record(skill, executor, evaluator, instance, messages);
            if (record != null) {
                records.add(record);
                recordInstances.add(instance);
            }
        }
        if (records.isEmpty()) {
            return;
        }
        List<ObjectNode> outputs = executor.execute(skill, records, messages);
        for (int index = 0; index < recordInstances.size() && index < outputs.size(); index++) {
            ObjectNode output = outputs.get(index);
            if (output == null) {
                continue;
            }
            for (OutputEntry declared : skill.outputs()) {
                JsonNode value = output.get(declared.name());
                if (value != null) {
                    recordInstances.get(index).node().annotate(declared.targetName(), value.deepCopy());
                }
            }
        }
    }

    /** The skill's inputs at {@code instance}; null, with the documented warning, when a required one is unusable. */
    private static ObjectNode record(SkillDefinition skill, SkillExecutor executor, InputEvaluator evaluator,
                                     Located instance, SkillMessages messages) {
        ObjectNode record = NODES.objectNode();
        Map<String, String> problems = new HashMap<>();
        for (InputEntry input : skill.inputs()) {
            PathValue value = evaluator.evaluate(input, instance, skill.context());
            if (!value.isMissing()) {
                record.set(input.name(), value.value().deepCopy());
                continue;
            }
            if (value.problem() != null) {
                problems.put(input.name(), value.problem());
            }
            if (executor.sendsMissingInputsAsNull()) {
                record.putNull(input.name());
            }
        }
        for (RequiredInput required : executor.requiredInputs()) {
            JsonNode value = record.get(required.name());
            InputEntry input = skill.input(required.name());
            String source = input == null ? null : input.sourceText();
            if (value == null || value.isNull()) {
                String problem = problems.get(required.name());
                messages.warning(skill, "Could not execute skill because one or more skill input was invalid. "
                    + "Required skill input is missing. Name: " + required.name() + ", Source: " + source + "."
                    + (problem == null ? "" : " " + problem));
                return null;
            }
            if (!required.type().accepts(value)) {
                messages.warning(skill, "Could not execute skill because one or more skill input was invalid. "
                    + "Required skill input was not of the expected type '" + required.type().label() + "'. Name: "
                    + required.name() + ", Source: " + source + ".");
                return null;
            }
        }
        return record;
    }
}
