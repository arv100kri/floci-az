package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.FacetExpression.DateUnit;
import io.floci.az.services.search.model.SearchJson;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Faceted navigation, per Learn's "Add faceted navigation" and "Faceted navigation examples": each expression of the
 * request's {@code facets} is counted over every document the query and filter matched, a document counting once per
 * bucket however many of its values (a collection, or a subfield of a complex collection) fall in it. In a hybrid
 * query with {@code countAndFacetMode} {@code countAllResults} that includes the text matches beyond
 * {@code maxTextRecallSize} ({@link SearchExecution#countOnlyMatches()}): per "Create a hybrid query", counts and
 * facets then "include all documents that matched the text query", the set {@code @odata.count} counts.
 * <ul>
 *   <li>value facets list distinct values as {@code {"count", "value"}}, the {@code count} most frequent (10 by
 *       default, every one with {@code count:0}), ordered by {@code sort} (count descending by default; ties by
 *       value);</li>
 *   <li>{@code values} facets list one {@code {"count", "to"}}, {@code {"count", "from", "to"}} ... {@code {"count",
 *       "from"}} bucket per range, empty ones included; a range includes its {@code from} and excludes its
 *       {@code to};</li>
 *   <li>{@code interval} facets list the non-empty buckets by ascending value, each value the bucket's lower bound
 *       (dates truncated to the unit in {@code timeoffset}, weeks starting on Monday).</li>
 * </ul>
 */
public final class FacetStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        if (execution.request().facets().isEmpty()) {
            return;
        }
        List<FacetExpression> expressions = new ArrayList<>();
        for (String expression : execution.request().facets()) {
            expressions.add(FacetExpression.parse(expression, execution.index().definition()));
        }
        LiveIndex index = execution.index();
        List<Integer> counted = countedOrdinals(execution);
        for (FacetExpression expression : expressions) {
            List<String> path = List.of(expression.field().path().split("/"));
            List<Set<Object>> documentValues = new ArrayList<>(counted.size());
            for (int ordinal : counted) {
                IndexedDocument document = index.document(ordinal);
                documentValues.add(values(DocumentValues.valueAt(document.values(), path)));
            }
            ArrayNode buckets;
            if (expression.boundaries() != null) {
                buckets = ranges(expression, documentValues);
            } else if (expression.interval() != null || expression.dateUnit() != null) {
                buckets = intervals(expression, documentValues);
            } else {
                buckets = distinctValues(expression, documentValues);
            }
            execution.facets().put(expression.name(), buckets);
        }
    }

    /** The documents facets count: every hit, and the matches that count toward {@code @odata.count} only. */
    private static List<Integer> countedOrdinals(SearchExecution execution) {
        BitSet countOnly = execution.countOnlyMatches();
        List<Integer> ordinals = new ArrayList<>(execution.hits().size() + countOnly.cardinality());
        for (SearchHit hit : execution.hits()) {
            ordinals.add(hit.ordinal());
        }
        for (int ordinal = countOnly.nextSetBit(0); ordinal >= 0; ordinal = countOnly.nextSetBit(ordinal + 1)) {
            ordinals.add(ordinal);
        }
        return ordinals;
    }

    /** The distinct facetable values of one document. */
    private static Set<Object> values(Object value) {
        Set<Object> values = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            list.forEach(element -> addValue(values, element));
        } else {
            addValue(values, value);
        }
        return values;
    }

    private static void addValue(Set<Object> values, Object value) {
        if (value == null || (value instanceof Double number && !Double.isFinite(number))) {
            return;
        }
        values.add(value);
    }

    private static ArrayNode distinctValues(FacetExpression expression, List<Set<Object>> documentValues) {
        Map<Object, Integer> counts = new HashMap<>();
        for (Set<Object> values : documentValues) {
            values.forEach(value -> counts.merge(value, 1, Integer::sum));
        }
        Comparator<Map.Entry<Object, Integer>> byValue = (left, right) -> compare(left.getKey(), right.getKey());
        Comparator<Map.Entry<Object, Integer>> byCount = Map.Entry.comparingByValue();
        List<Map.Entry<Object, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(byCount.reversed().thenComparing(byValue));
        if (expression.count() > 0 && entries.size() > expression.count()) {
            entries = new ArrayList<>(entries.subList(0, expression.count()));
        }
        entries.sort(switch (expression.sort()) {
            case COUNT_DESCENDING -> byCount.reversed().thenComparing(byValue);
            case COUNT_ASCENDING -> byCount.thenComparing(byValue);
            case VALUE_ASCENDING -> byValue;
            case VALUE_DESCENDING -> byValue.reversed();
        });
        ArrayNode buckets = SearchJson.NODES.arrayNode();
        for (Map.Entry<Object, Integer> entry : entries) {
            ObjectNode bucket = buckets.addObject();
            bucket.put("count", entry.getValue());
            put(bucket, "value", entry.getKey());
        }
        return buckets;
    }

    private static ArrayNode ranges(FacetExpression expression, List<Set<Object>> documentValues) {
        List<Object> boundaries = expression.boundaries();
        int[] counts = new int[boundaries.size() + 1];
        for (Set<Object> values : documentValues) {
            boolean[] counted = new boolean[counts.length];
            for (Object value : values) {
                int bucket = 0;
                while (bucket < boundaries.size() && compare(value, boundaries.get(bucket)) >= 0) {
                    bucket++;
                }
                counted[bucket] = true;
            }
            for (int bucket = 0; bucket < counts.length; bucket++) {
                if (counted[bucket]) {
                    counts[bucket]++;
                }
            }
        }
        ArrayNode buckets = SearchJson.NODES.arrayNode();
        for (int bucket = 0; bucket < counts.length; bucket++) {
            ObjectNode node = buckets.addObject();
            node.put("count", counts[bucket]);
            if (bucket > 0) {
                put(node, "from", boundaries.get(bucket - 1));
            }
            if (bucket < boundaries.size()) {
                put(node, "to", boundaries.get(bucket));
            }
        }
        return buckets;
    }

    private static ArrayNode intervals(FacetExpression expression, List<Set<Object>> documentValues) {
        Map<Object, Integer> counts = new TreeMap<>(FacetStage::compare);
        for (Set<Object> values : documentValues) {
            Set<Object> buckets = new LinkedHashSet<>();
            for (Object value : values) {
                buckets.add(expression.dateUnit() != null
                    ? dateBucket((Instant) value, expression.dateUnit(), expression.timeOffset())
                    : numericBucket(value, expression.interval()));
            }
            buckets.forEach(bucket -> counts.merge(bucket, 1, Integer::sum));
        }
        ArrayNode buckets = SearchJson.NODES.arrayNode();
        counts.forEach((value, count) -> {
            ObjectNode bucket = buckets.addObject();
            bucket.put("count", count);
            put(bucket, "value", value);
        });
        return buckets;
    }

    private static Object numericBucket(Object value, double interval) {
        if (value instanceof Long number && interval == Math.rint(interval) && interval <= Long.MAX_VALUE) {
            long width = (long) interval;
            return Math.floorDiv(number, width) * width;
        }
        double number = ((Number) value).doubleValue();
        return Math.floor(number / interval) * interval;
    }

    private static Instant dateBucket(Instant value, DateUnit unit, ZoneOffset offset) {
        OffsetDateTime local = value.atOffset(offset);
        OffsetDateTime start = switch (unit) {
            case MINUTE -> local.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> local.truncatedTo(ChronoUnit.HOURS);
            case DAY -> local.truncatedTo(ChronoUnit.DAYS);
            case WEEK -> local.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> local.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
            case QUARTER -> local.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
                .withMonth((local.getMonthValue() - 1) / 3 * 3 + 1);
            case YEAR -> local.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
        };
        return start.toInstant();
    }

    /** Orders facet values of one field: numbers numerically, strings ordinally, false before true, dates by time. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            if (left instanceof Long leftLong && right instanceof Long rightLong) {
                return Long.compare(leftLong, rightLong);
            }
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        if (left instanceof Comparable comparable && left.getClass() == right.getClass()) {
            return comparable.compareTo(right);
        }
        return left.toString().compareTo(right.toString());
    }

    private static void put(ObjectNode node, String name, Object value) {
        switch (value) {
            case Long number -> node.put(name, number);
            case Double number -> node.put(name, number);
            case Boolean bool -> node.put(name, bool);
            case Instant instant -> node.put(name, DocumentCoercer.formatDateTime(instant));
            default -> node.put(name, value.toString());
        }
    }
}
