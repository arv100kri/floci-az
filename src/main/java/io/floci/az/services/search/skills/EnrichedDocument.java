package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.skills.EnrichmentPath.Segment;

import java.util.ArrayList;
import java.util.List;

/**
 * The enrichment tree of one document: the {@code /document} node the indexer built, and every annotation skills add.
 * It resolves the annotation language's paths:
 * <ul>
 *   <li>a skill {@link #instances(EnrichmentPath) context} names the nodes the skill runs once for: one per match of
 *       its {@code *} segments, skipping nodes that are missing or null;</li>
 *   <li>an input path is {@link #evaluate evaluated} relative to one of those nodes: the segments it shares with the
 *       context are bound to that node's position, so {@code /document/pages/*} in a skill whose context is
 *       {@code /document/pages/*} is the current page, and each remaining {@code *} enumerates an array, producing a
 *       flat array of every match.</li>
 * </ul>
 */
final class EnrichedDocument {

    private final EnrichmentNode root;

    /** A tree over a copy of {@code document}; the caller's node is never modified. */
    EnrichedDocument(ObjectNode document) {
        this.root = new EnrichmentNode("/document", document == null ? JsonNodeFactory.instance.objectNode()
            : document.deepCopy());
    }

    EnrichmentNode root() {
        return root;
    }

    /** The root as a scope: the position paths are evaluated from when nothing binds them. */
    Located rootScope() {
        return new Located(List.of(root));
    }

    /** Every non-null node {@code context} names, in document order, each with the nodes on its way from the root. */
    List<Located> instances(EnrichmentPath context) {
        List<Located> found = new ArrayList<>();
        List<EnrichmentNode> chain = new ArrayList<>();
        chain.add(root);
        walk(context, 1, chain, found);
        return found;
    }

    /** The instances of {@code path} below {@code scope}, sharing the segments {@code path} has in common with it. */
    List<Located> instances(EnrichmentPath path, Located scope, EnrichmentPath scopePattern) {
        int shared = Math.max(1, Math.min(path.commonPrefix(scopePattern), scope.chain().size()));
        List<EnrichmentNode> chain = new ArrayList<>(scope.chain().subList(0, shared));
        List<Located> found = new ArrayList<>();
        walk(path, shared, chain, found);
        return found;
    }

    private static void walk(EnrichmentPath pattern, int position, List<EnrichmentNode> chain, List<Located> found) {
        EnrichmentNode node = chain.get(chain.size() - 1);
        if (position == pattern.length()) {
            if (!node.isNull()) {
                found.add(new Located(List.copyOf(chain)));
            }
            return;
        }
        Segment segment = pattern.segment(position);
        switch (segment.kind()) {
            case NAME -> {
                EnrichmentNode child = node.child(segment.name()).node();
                if (child != null) {
                    descend(pattern, position, chain, child, found);
                }
            }
            case STAR -> {
                List<EnrichmentNode> elements = node.elements();
                if (elements != null) {
                    for (EnrichmentNode element : elements) {
                        descend(pattern, position, chain, element, found);
                    }
                }
            }
            case HASH -> descend(pattern, position, chain, node, found);
        }
    }

    private static void descend(EnrichmentPath pattern, int position, List<EnrichmentNode> chain, EnrichmentNode next,
                                List<Located> found) {
        chain.add(next);
        walk(pattern, position + 1, chain, found);
        chain.remove(chain.size() - 1);
    }

    /**
     * The value {@code path} selects from {@code scope}, whose own path pattern is {@code scopePattern}. Segments
     * {@code path} shares with the pattern resolve to the scope's position; with no {@code *} left to enumerate the
     * result is the one node's value, otherwise an array of every match's value.
     */
    PathValue evaluate(EnrichmentPath path, Located scope, EnrichmentPath scopePattern) {
        int shared = Math.max(1, Math.min(path.commonPrefix(scopePattern), scope.chain().size()));
        EnrichmentNode start = scope.chain().get(shared - 1);
        Collector collector = new Collector();
        collect(path, shared, start, collector);
        if (collector.enumerated) {
            if (collector.nodes.isEmpty() && collector.problem != null) {
                return new PathValue(null, collector.problem);
            }
            ArrayNode values = JsonNodeFactory.instance.arrayNode();
            for (EnrichmentNode node : collector.nodes) {
                values.add(node.value() == null ? JsonNodeFactory.instance.nullNode() : node.value());
            }
            return new PathValue(values, null);
        }
        if (collector.nodes.isEmpty()) {
            return new PathValue(null, collector.problem);
        }
        return new PathValue(collector.nodes.get(0).value(), null);
    }

    /** {@link #evaluate} from the root: the form output field mappings and unbound paths use. */
    PathValue evaluate(EnrichmentPath path) {
        return evaluate(path, rootScope(), EnrichmentPath.ROOT);
    }

    private static void collect(EnrichmentPath path, int position, EnrichmentNode node, Collector collector) {
        if (position == path.length()) {
            collector.nodes.add(node);
            return;
        }
        Segment segment = path.segment(position);
        switch (segment.kind()) {
            case NAME -> {
                EnrichmentNode.Lookup lookup = node.child(segment.name());
                if (lookup.node() == null) {
                    collector.fail(lookup.problem());
                } else {
                    collect(path, position + 1, lookup.node(), collector);
                }
            }
            case STAR -> {
                collector.enumerated = true;
                if (node.isNull()) {
                    collector.fail("Missing value " + node.path() + ".");
                    return;
                }
                List<EnrichmentNode> elements = node.elements();
                if (elements == null) {
                    collector.fail("Cannot iterate over non-array " + node.path() + ".");
                    return;
                }
                for (EnrichmentNode element : elements) {
                    collect(path, position + 1, element, collector);
                }
            }
            case HASH -> collect(path, position + 1, node, collector);
        }
    }

    /** What a path evaluation collects. */
    private static final class Collector {
        private final List<EnrichmentNode> nodes = new ArrayList<>();
        private boolean enumerated;
        private String problem;

        void fail(String reason) {
            if (problem == null) {
                problem = reason;
            }
        }
    }

    /**
     * A node a context path matched, with the nodes from the root down to it, one per path segment.
     *
     * @param chain the nodes, the root first
     */
    record Located(List<EnrichmentNode> chain) {

        Located {
            chain = List.copyOf(chain);
        }

        EnrichmentNode node() {
            return chain.get(chain.size() - 1);
        }
    }

    /**
     * The result of evaluating a path.
     *
     * @param value   the value; null when the path selects nothing
     * @param problem why it selects nothing, in the indexer's documented wording; null when it selected something
     */
    record PathValue(JsonNode value, String problem) {

        boolean isMissing() {
            return value == null;
        }

        /** Whether there is no usable value: missing, or a JSON null. */
        boolean isMissingOrNull() {
            return value == null || value.isNull() || value.isMissingNode();
        }
    }
}
