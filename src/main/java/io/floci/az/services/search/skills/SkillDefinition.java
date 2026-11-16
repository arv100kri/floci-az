package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One skill of a skillset as the pipeline runs it.
 *
 * @param position  the skill's 1-based position in the {@code skills} array
 * @param odataType the {@code @odata.type}
 * @param name      the name, {@code #position} when the definition gives none
 * @param context   the context, {@code /document} by default
 * @param inputs    the inputs
 * @param outputs   the outputs
 * @param json      the skill's definition
 */
record SkillDefinition(int position, String odataType, String name, EnrichmentPath context, List<InputEntry> inputs,
                       List<OutputEntry> outputs, ObjectNode json) {

    SkillDefinition {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    /**
     * Reads a skill definition.
     *
     * @param json     the skill
     * @param position its 1-based position in the skills array
     * @throws IllegalArgumentException when the skill is not valid, with the reason
     */
    static SkillDefinition parse(ObjectNode json, int position) {
        String type = text(json, "@odata.type");
        if (type == null) {
            throw new IllegalArgumentException("the skill has no '@odata.type'");
        }
        String name = text(json, "name");
        if (name == null || name.isEmpty()) {
            name = "#" + position;
        }
        String contextText = text(json, "context");
        EnrichmentPath context = contextText == null ? EnrichmentPath.ROOT : parsePath(contextText, "context");
        List<InputEntry> inputs = InputEntry.parseAll(json.get("inputs"), context);
        List<OutputEntry> outputs = new ArrayList<>();
        JsonNode outputArray = json.get("outputs");
        if (outputArray instanceof ArrayNode array) {
            for (JsonNode output : array) {
                String outputName = text(output, "name");
                if (outputName == null || outputName.isEmpty()) {
                    throw new IllegalArgumentException("an output has no name");
                }
                String target = text(output, "targetName");
                outputs.add(new OutputEntry(outputName, target == null || target.isEmpty() ? outputName : target));
            }
        } else if (outputArray != null && !outputArray.isNull()) {
            throw new IllegalArgumentException("'outputs' must be an array");
        }
        return new SkillDefinition(position, type, name, context, inputs, outputs, json);
    }

    /** The short type name, {@code SplitSkill} for {@code #Microsoft.Skills.Text.SplitSkill}. */
    String shortType() {
        int dot = odataType.lastIndexOf('.');
        return dot < 0 ? odataType : odataType.substring(dot + 1);
    }

    /** The name the indexer reports the skill under: {@code Enrichment.WebApiSkill.mySkill}. */
    String operationName() {
        return "Enrichment." + shortType() + "." + name;
    }

    /** The input called {@code inputName}, or null. */
    InputEntry input(String inputName) {
        for (InputEntry input : inputs) {
            if (input.name().equals(inputName)) {
                return input;
            }
        }
        return null;
    }

    /** Every path the skill writes: one per output, below the context. */
    List<EnrichmentPath> writes() {
        List<EnrichmentPath> writes = new ArrayList<>(outputs.size());
        for (OutputEntry output : outputs) {
            writes.add(context.child(output.targetName()));
        }
        return writes;
    }

    /** Every path the skill reads: its context and every input path, expression path and source context. */
    List<EnrichmentPath> reads() {
        List<EnrichmentPath> reads = new ArrayList<>();
        reads.add(context);
        for (InputEntry input : inputs) {
            input.collectReads(reads);
        }
        return reads;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    static EnrichmentPath parsePath(String text, String what) {
        try {
            return EnrichmentPath.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the " + what + " '" + text + "' is invalid: " + e.getMessage()
                + "; paths start with '/document'");
        }
    }

    /**
     * A skill input, an index projection mapping or a nested shape input ({@code InputFieldMappingEntry}).
     *
     * @param name          the input name
     * @param path          the source path, when the source is a path
     * @param expression    the source expression, when the source starts with {@code =}
     * @param sourceContext the context of a shape built from nested inputs
     * @param inputs        the nested inputs of a shape
     */
    record InputEntry(String name, EnrichmentPath path, InputExpression expression, EnrichmentPath sourceContext,
                      List<InputEntry> inputs) {

        InputEntry {
            inputs = List.copyOf(inputs);
        }

        /** The {@code source} as written: the path or the expression text; null for a shape. */
        String sourceText() {
            if (expression != null) {
                return expression.text();
            }
            return path == null ? null : path.toString();
        }

        static List<InputEntry> parseAll(JsonNode array, EnrichmentPath defaultSourceContext) {
            List<InputEntry> entries = new ArrayList<>();
            if (array == null || array.isNull()) {
                return entries;
            }
            if (!(array instanceof ArrayNode items)) {
                throw new IllegalArgumentException("'inputs' must be an array");
            }
            for (JsonNode item : items) {
                entries.add(parse(item, defaultSourceContext));
            }
            return entries;
        }

        static InputEntry parse(JsonNode node, EnrichmentPath defaultSourceContext) {
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalArgumentException("each input must be a JSON object");
            }
            String name = text(object, "name");
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("an input has no name");
            }
            String source = text(object, "source");
            String sourceContextText = text(object, "sourceContext");
            JsonNode nested = object.get("inputs");
            boolean hasNested = nested instanceof ArrayNode nestedArray && !nestedArray.isEmpty();
            if (source != null && !source.isBlank()) {
                if (sourceContextText != null || hasNested) {
                    throw new IllegalArgumentException("the input '" + name + "' sets 'source' together with "
                        + "'sourceContext' or 'inputs'; they are mutually exclusive");
                }
                if (InputExpression.isExpression(source)) {
                    try {
                        return new InputEntry(name, null, InputExpression.parse(source), null, List.of());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("the input '" + name + "' has an invalid source: "
                            + e.getMessage());
                    }
                }
                return new InputEntry(name, parsePath(source, "source of input '" + name + "'"), null, null,
                    List.of());
            }
            if (!hasNested) {
                throw new IllegalArgumentException("the input '" + name + "' has neither a 'source' nor nested "
                    + "'inputs'");
            }
            EnrichmentPath sourceContext = sourceContextText == null ? defaultSourceContext
                : parsePath(sourceContextText, "sourceContext of input '" + name + "'");
            return new InputEntry(name, null, null, sourceContext, parseAll(nested, sourceContext));
        }

        void collectReads(List<EnrichmentPath> reads) {
            if (path != null) {
                reads.add(path);
            }
            if (expression != null) {
                reads.addAll(expression.paths());
            }
            if (sourceContext != null) {
                reads.add(sourceContext);
            }
            for (InputEntry input : inputs) {
                input.collectReads(reads);
            }
        }
    }

    /**
     * A skill output.
     *
     * @param name       the output the skill produces
     * @param targetName the name of the node it becomes, the output name by default
     */
    record OutputEntry(String name, String targetName) {
    }
}
