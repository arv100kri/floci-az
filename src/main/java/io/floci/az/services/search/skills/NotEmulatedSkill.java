package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Every skill the emulator accepts in a definition but cannot run, such as OCR, image analysis, entity recognition,
 * key phrases, language detection, sentiment, PII detection and translation, which call Azure AI services models. A
 * document that reaches one fails with one error naming the skill.
 */
final class NotEmulatedSkill implements SkillExecutor {

    @Override
    public boolean sendsMissingInputsAsNull() {
        return true;
    }

    @Override
    public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
        messages.error(skill, "Could not execute skill because the skill '" + skill.name() + "' of type '"
            + skill.odataType() + "' is not emulated by floci-az: it needs an Azure AI services model.");
        List<ObjectNode> results = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            results.add(null);
        }
        return results;
    }
}
