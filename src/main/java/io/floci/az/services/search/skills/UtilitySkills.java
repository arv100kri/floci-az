package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The utility skills that only rearrange the enrichment tree: {@code #Microsoft.Skills.Util.ShaperSkill} and
 * {@code #Microsoft.Skills.Util.ConditionalSkill}.
 */
final class UtilitySkills {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private UtilitySkills() {
    }

    /**
     * The Shaper skill: its single output, {@code output}, is an object with one member per input, each input's value
     * (an inline shape with {@code sourceContext} and nested inputs included). Inputs that select nothing are left out.
     */
    static final class Shaper implements SkillExecutor {

        @Override
        public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
            List<ObjectNode> results = new ArrayList<>(records.size());
            for (ObjectNode record : records) {
                ObjectNode result = NODES.objectNode();
                result.set("output", record);
                results.add(result);
            }
            return results;
        }
    }

    /**
     * The Conditional skill: {@code output} is {@code whenTrue} when {@code condition} is true and {@code whenFalse}
     * otherwise; an input that selects nothing is {@code null}. A condition that is not a Boolean skips the record with
     * the documented warning.
     */
    static final class Conditional implements SkillExecutor {

        @Override
        public List<RequiredInput> requiredInputs() {
            return List.of(new RequiredInput("condition", InputType.ANY));
        }

        @Override
        public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
            List<ObjectNode> results = new ArrayList<>(records.size());
            for (ObjectNode record : records) {
                JsonNode condition = record.get("condition");
                if (!condition.isBoolean()) {
                    SkillDefinition.InputEntry input = skill.input("condition");
                    messages.warning(skill, "Could not execute skill because one or more skill input was invalid. "
                        + "Required skill input was not of the expected type 'Boolean'. Name: 'condition', Source: '"
                        + (input == null ? null : input.sourceText()) + "'.");
                    results.add(null);
                    continue;
                }
                JsonNode chosen = record.get(condition.booleanValue() ? "whenTrue" : "whenFalse");
                ObjectNode result = NODES.objectNode();
                result.set("output", chosen == null ? NODES.nullNode() : chosen);
                results.add(result);
            }
            return results;
        }
    }
}
