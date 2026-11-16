package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code #Microsoft.Skills.Text.MergeSkill}: inserts each of {@code itemsToInsert}, wrapped in {@code insertPreTag}
 * and {@code insertPostTag} (a space by default), into {@code text} at its {@code offsets} entry. Without offsets, or
 * with a different number of offsets than items, the items go at the end of the text; without text, the items are
 * concatenated. Offsets that cannot be read put the items at the end with the documented message, as a warning since
 * the skill still produces its output. {@code mergedOffsets} gives, for each item, the position in {@code mergedText}
 * where its insertion (pre-tag included) starts.
 */
final class MergeSkill implements SkillExecutor {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override
    public List<RequiredInput> requiredInputs() {
        return List.of(new RequiredInput("itemsToInsert", InputType.ARRAY));
    }

    @Override
    public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
        String preTag = tag(skill.json(), "insertPreTag");
        String postTag = tag(skill.json(), "insertPostTag");
        List<ObjectNode> results = new ArrayList<>(records.size());
        for (ObjectNode record : records) {
            results.add(merge(skill, record, preTag, postTag, messages));
        }
        return results;
    }

    private static String tag(ObjectNode json, String name) {
        JsonNode value = json.get(name);
        return value != null && value.isTextual() ? value.textValue() : " ";
    }

    private static ObjectNode merge(SkillDefinition skill, ObjectNode record, String preTag, String postTag,
                                    SkillMessages messages) {
        JsonNode textNode = record.get("text");
        String text = textNode == null || textNode.isNull() ? "" : asText(textNode);
        List<String> items = new ArrayList<>();
        for (JsonNode item : record.get("itemsToInsert")) {
            items.add(item.isNull() ? "" : asText(item));
        }
        int[] offsets = null;
        JsonNode offsetsNode = record.get("offsets");
        if (textNode != null && offsetsNode instanceof ArrayNode offsetArray && offsetArray.size() == items.size()) {
            offsets = parseOffsets(offsetArray, text.length());
            if (offsets == null) {
                messages.warning(skill, "One or more offset values were invalid and couldn't be parsed. Items were "
                    + "inserted at the end of the text");
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            order.add(index);
        }
        if (offsets != null) {
            int[] positions = offsets;
            order.sort(Comparator.comparingInt(index -> positions[index]));
        }
        StringBuilder merged = new StringBuilder(text.length() + 16 * items.size());
        int[] mergedOffsets = new int[items.size()];
        int copied = 0;
        for (int index : order) {
            int at = offsets == null ? text.length() : offsets[index];
            merged.append(text, copied, at);
            copied = at;
            mergedOffsets[index] = merged.length();
            merged.append(preTag).append(items.get(index)).append(postTag);
        }
        merged.append(text, copied, text.length());
        ObjectNode result = NODES.objectNode();
        result.put("mergedText", merged.toString());
        ArrayNode positions = result.putArray("mergedOffsets");
        for (int offset : mergedOffsets) {
            positions.add(offset);
        }
        return result;
    }

    /** The offsets as integers within the text; null when one is not a number in range. */
    private static int[] parseOffsets(ArrayNode array, int textLength) {
        int[] offsets = new int[array.size()];
        for (int index = 0; index < offsets.length; index++) {
            JsonNode value = array.get(index);
            long offset;
            if (value.isIntegralNumber() && value.canConvertToLong()) {
                offset = value.longValue();
            } else if (value.isTextual() && value.textValue().trim().matches("\\d{1,10}")) {
                offset = Long.parseLong(value.textValue().trim());
            } else {
                return null;
            }
            if (offset < 0 || offset > textLength) {
                return null;
            }
            offsets[index] = (int) offset;
        }
        return offsets;
    }

    private static String asText(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }
}
