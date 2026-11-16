package io.floci.az.services.search.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * The warnings and errors enriching one document produces, in the order they occur. Each message starts with the name
 * the indexer reports the operation under ({@code Enrichment.WebApiSkill.mySkill},
 * {@code Projection.SearchIndex.OutputFieldMapping.myField}), followed by the documented message and its details.
 * Not thread-safe: work that runs in parallel collects into its own instance and is {@link #addAll merged} in order.
 */
final class SkillMessages {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    void warning(SkillDefinition skill, String message) {
        warnings.add(skill.operationName() + ": " + message);
    }

    void error(SkillDefinition skill, String message) {
        errors.add(skill.operationName() + ": " + message);
    }

    void warning(String operation, String message) {
        warnings.add(operation + ": " + message);
    }

    void error(String operation, String message) {
        errors.add(operation + ": " + message);
    }

    void addAll(SkillMessages other) {
        warnings.addAll(other.warnings);
        errors.addAll(other.errors);
    }

    List<String> warnings() {
        return List.copyOf(warnings);
    }

    List<String> errors() {
        return List.copyOf(errors);
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }
}
