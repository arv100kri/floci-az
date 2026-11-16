package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Runs one kind of skill. The pipeline evaluates the skill's inputs once per context node into a record (input name to
 * value), checks the {@link #requiredInputs() required inputs}, and hands the remaining records over in one call, so
 * that a skill that calls out can batch them.
 */
interface SkillExecutor {

    /** The inputs a record must have, with their types; a record without one is skipped with a warning. */
    default List<RequiredInput> requiredInputs() {
        return List.of();
    }

    /** Whether an input whose source selects nothing is sent as {@code null} instead of being left out. */
    default boolean sendsMissingInputsAsNull() {
        return false;
    }

    /**
     * Runs the skill.
     *
     * @param skill    the skill
     * @param records  one record per context node, in document order
     * @param messages where warnings and errors go
     * @return each record's outputs by the skill's output names, in record order; null for a record with none
     */
    List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages);

    /**
     * An input a skill cannot run without.
     *
     * @param name the input name
     * @param type the type its value must have
     */
    record RequiredInput(String name, InputType type) {
    }

    /** The JSON type a required input must have. */
    enum InputType {
        STRING("String"),
        ARRAY("Array"),
        OBJECT("Object"),
        ANY("Any");

        private final String label;

        InputType(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        boolean accepts(JsonNode value) {
            return switch (this) {
                case STRING -> value.isTextual();
                case ARRAY -> value.isArray();
                case OBJECT -> value.isObject();
                case ANY -> true;
            };
        }
    }
}
