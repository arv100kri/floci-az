package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.skills.EnrichedDocument.Located;
import io.floci.az.services.search.skills.EnrichedDocument.PathValue;
import io.floci.az.services.search.skills.SkillDefinition.InputEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates an {@code InputFieldMappingEntry} (a skill input, a nested shape input or an index projection mapping)
 * at one position of the enrichment tree: a path, an expression, or a shape built from nested inputs at each node of
 * its {@code sourceContext} (an array of shapes when the source context enumerates beyond the position, else one).
 */
final class InputEvaluator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final EnrichedDocument document;

    InputEvaluator(EnrichedDocument document) {
        this.document = document;
    }

    /**
     * The value of {@code input} at {@code scope}.
     *
     * @param input        the input
     * @param scope        the position, such as one context node of a skill
     * @param scopePattern the path pattern {@code scope} is an instance of
     */
    PathValue evaluate(InputEntry input, Located scope, EnrichmentPath scopePattern) {
        if (input.expression() != null) {
            try {
                JsonNode value = input.expression().evaluate(
                    path -> document.evaluate(path, scope, scopePattern).value());
                return new PathValue(value, null);
            } catch (InputExpression.EvaluationException e) {
                return new PathValue(null, "The expression '" + input.expression().text() + "' could not be "
                    + "evaluated: " + e.getMessage() + ".");
            }
        }
        if (input.path() != null) {
            return document.evaluate(input.path(), scope, scopePattern);
        }
        EnrichmentPath sourceContext = input.sourceContext();
        List<Located> matches = document.instances(sourceContext, scope, scopePattern);
        List<ObjectNode> shapes = new ArrayList<>(matches.size());
        for (Located match : matches) {
            ObjectNode shape = NODES.objectNode();
            for (InputEntry nested : input.inputs()) {
                PathValue value = evaluate(nested, match, sourceContext);
                if (!value.isMissing()) {
                    shape.set(nested.name(), value.value().deepCopy());
                }
            }
            shapes.add(shape);
        }
        if (enumerates(sourceContext, scopePattern)) {
            ArrayNode array = NODES.arrayNode();
            shapes.forEach(array::add);
            return new PathValue(array, null);
        }
        return shapes.isEmpty() ? new PathValue(null, "Missing value " + sourceContext + ".")
            : new PathValue(shapes.get(0), null);
    }

    /** Whether {@code path} has a {@code *} beyond the segments it shares with {@code scopePattern}. */
    private static boolean enumerates(EnrichmentPath path, EnrichmentPath scopePattern) {
        int shared = path.commonPrefix(scopePattern);
        for (int index = shared; index < path.length(); index++) {
            if (path.segment(index).kind() == EnrichmentPath.Kind.STAR) {
                return true;
            }
        }
        return false;
    }
}
