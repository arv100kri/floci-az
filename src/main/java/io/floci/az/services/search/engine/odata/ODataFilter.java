package io.floci.az.services.search.engine.odata;

import io.floci.az.services.search.engine.analysis.IndexAnalysis;
import io.floci.az.services.search.engine.analysis.LexicalNormalizer;
import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.GeoPoint;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.odata.FilterExpression.And;
import io.floci.az.services.search.engine.odata.FilterExpression.BooleanConstant;
import io.floci.az.services.search.engine.odata.FilterExpression.Comparison;
import io.floci.az.services.search.engine.odata.FilterExpression.ComparisonOperator;
import io.floci.az.services.search.engine.odata.FilterExpression.Condition;
import io.floci.az.services.search.engine.odata.FilterExpression.Lambda;
import io.floci.az.services.search.engine.odata.FilterExpression.Not;
import io.floci.az.services.search.engine.odata.FilterExpression.Operand;
import io.floci.az.services.search.engine.odata.FilterExpression.Or;
import io.floci.az.services.search.model.EdmType;
import io.floci.az.services.search.model.FieldType;
import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@code $filter} expression bound to an index: parsed, validated against the schema (fields exist and are
 * filterable, operand types are compatible, {@code null} only with {@code eq} and {@code ne}, collections only
 * through {@code any}/{@code all}, the documented lambda limits for string collections) and compiled to a predicate
 * over a document's typed values. Immutable and thread-safe.
 *
 * <p>Comparisons follow Learn's "OData comparison operators": strings compare case-sensitively (after the field's
 * normalizer, if any), {@code null} equals only {@code null} and is neither less nor greater than anything, and
 * {@code NaN} equals nothing. {@code search.ismatch} and {@code search.ismatchscoring} calls are evaluated by the text
 * engine once per query; {@link #fullTextCalls()} lists them and {@link #matches} reads their results.</p>
 */
public final class ODataFilter {

    private final Predicate predicate;
    private final List<FullTextCall> fullTextCalls;

    private ODataFilter(Predicate predicate, List<FullTextCall> fullTextCalls) {
        this.predicate = predicate;
        this.fullTextCalls = List.copyOf(fullTextCalls);
    }

    /** Parses and binds {@code text}; an invalid expression is a 400. */
    public static ODataFilter compile(String text, SearchIndexDefinition definition, IndexAnalysis analysis) {
        FilterExpression expression = FilterParser.parse(text);
        Binder binder = new Binder(definition, analysis);
        Predicate predicate = binder.bind(expression, Scope.ROOT);
        return new ODataFilter(predicate, binder.calls);
    }

    /** The {@code search.ismatch} and {@code search.ismatchscoring} calls, which the caller runs before matching. */
    public List<FullTextCall> fullTextCalls() {
        return fullTextCalls;
    }

    /** Whether {@code document} satisfies the filter, given the results of the full-text calls. */
    public boolean matches(IndexedDocument document, FullTextMatches matches) {
        return predicate.test(new Frame(document, Map.of(), matches));
    }

    /**
     * A {@code search.ismatch} or {@code search.ismatchscoring} call.
     *
     * @param id           the call's index in {@link #fullTextCalls()}
     * @param search       the query text
     * @param searchFields the fields to search; empty for every searchable field
     * @param queryType    the query syntax
     * @param searchMode   the search mode
     * @param scoring      whether matches add their relevance score to the document's score
     */
    public record FullTextCall(int id, String search, List<String> searchFields, QueryType queryType,
                               SearchMode searchMode, boolean scoring) {
    }

    /** Which documents each full-text call matched. */
    @FunctionalInterface
    public interface FullTextMatches {

        FullTextMatches NONE = (callId, ordinal) -> false;

        boolean matches(int callId, int ordinal);
    }

    @FunctionalInterface
    private interface Predicate {
        boolean test(Frame frame);
    }

    @FunctionalInterface
    private interface Value {
        Object evaluate(Frame frame);
    }

    private record Frame(IndexedDocument document, Map<String, Object> variables, FullTextMatches matches) {

        Frame with(String variable, Object value) {
            Map<String, Object> bound = new HashMap<>(variables);
            bound.put(variable, value);
            return new Frame(document, bound, matches);
        }
    }

    /** The range variables in scope: each name bound to the collection field it ranges over. */
    private record Scope(Map<String, SearchFieldDefinition> variables, boolean insideLambda) {

        static final Scope ROOT = new Scope(Map.of(), false);

        Scope with(String variable, SearchFieldDefinition collection) {
            Map<String, SearchFieldDefinition> bound = new HashMap<>(variables);
            bound.put(variable, collection);
            return new Scope(bound, true);
        }
    }

    /**
     * A resolved variable: a field path from the document, or a range variable and the sub-path after it.
     *
     * @param variable    the range variable, or null for a document field
     * @param path        the segments after the variable (all segments for a document field)
     * @param field       the field the reference ends at
     * @param type        the type of the value it produces (a range variable over a collection produces elements)
     * @param multiValued whether it produces a list (a collection, or a path through one)
     */
    private record Reference(String variable, List<String> path, SearchFieldDefinition field, FieldType type,
                             boolean multiValued) implements Value {

        @Override
        public Object evaluate(Frame frame) {
            if (variable == null) {
                return DocumentValues.valueAt(frame.document().values(), path);
            }
            Object bound = frame.variables().get(variable);
            if (path.isEmpty()) {
                return bound;
            }
            if (bound instanceof Map<?, ?> complex) {
                @SuppressWarnings("unchecked")
                Map<String, Object> values = (Map<String, Object>) complex;
                return DocumentValues.valueAt(values, path);
            }
            return null;
        }

        String text() {
            List<String> segments = new ArrayList<>();
            if (variable != null) {
                segments.add(variable);
            }
            segments.addAll(path);
            return String.join("/", segments);
        }
    }

    private static final class Binder {
        private final SearchIndexDefinition definition;
        private final IndexAnalysis analysis;
        private final List<FullTextCall> calls = new ArrayList<>();

        Binder(SearchIndexDefinition definition, IndexAnalysis analysis) {
            this.definition = definition;
            this.analysis = analysis;
        }

        Predicate bind(FilterExpression expression, Scope scope) {
            return switch (expression) {
                case And and -> {
                    Predicate left = bind(and.left(), scope);
                    Predicate right = bind(and.right(), scope);
                    yield frame -> left.test(frame) && right.test(frame);
                }
                case Or or -> {
                    Predicate left = bind(or.left(), scope);
                    Predicate right = bind(or.right(), scope);
                    yield frame -> left.test(frame) || right.test(frame);
                }
                case Not not -> {
                    Predicate operand = bind(not.operand(), scope);
                    yield frame -> !operand.test(frame);
                }
                case BooleanConstant constant -> {
                    boolean value = constant.value();
                    yield frame -> value;
                }
                case Condition condition -> condition(condition.operand(), scope);
                case Comparison comparison -> comparison(comparison, scope);
                case Lambda lambda -> lambda(lambda, scope);
            };
        }

        private Predicate condition(Operand operand, Scope scope) {
            if (operand instanceof Operand.Path path) {
                Reference reference = reference(path, scope);
                requireSingleValued(reference);
                if (reference.type().element() != EdmType.BOOLEAN) {
                    throw invalid("The expression '" + reference.text() + "' is not a boolean field; a filter must "
                        + "evaluate to true or false.");
                }
                return frame -> Boolean.TRUE.equals(reference.evaluate(frame));
            }
            if (operand instanceof Operand.Call call) {
                return booleanCall(call, scope);
            }
            throw invalid("The filter must be a boolean expression.");
        }

        private Predicate comparison(Comparison comparison, Scope scope) {
            Operand left = comparison.left();
            Operand right = comparison.right();
            ComparisonOperator operator = comparison.operator();
            if (isConstant(left) && !isConstant(right)) {
                Operand swap = left;
                left = right;
                right = swap;
                operator = operator.mirrored();
            }
            if (isConstant(left)) {
                throw invalid("A comparison must have a field, range variable or function call on one side; "
                    + "comparing two constants is not supported.");
            }
            if (!(right instanceof Operand.Constant constant)) {
                throw invalid("A comparison must have a constant on one side; comparing two fields or function "
                    + "calls is not supported.");
            }
            if (left instanceof Operand.Call call) {
                return callComparison(call, operator, constant, scope);
            }
            Reference reference = reference((Operand.Path) left, scope);
            requireSingleValued(reference);
            Object literal = checkLiteral(reference, operator, constant);
            ComparisonOperator effective = operator;
            return frame -> compare(reference.evaluate(frame), effective, literal);
        }

        private Predicate callComparison(Operand.Call call, ComparisonOperator operator, Operand.Constant constant,
                                         Scope scope) {
            if (call.name().equals("geo.distance")) {
                Value distance = geoDistance(call, scope);
                if (!operator.isRange()) {
                    throw invalid("geo.distance can only be compared with 'lt', 'le', 'gt' or 'ge'.");
                }
                if (!constant.isNumber()) {
                    throw invalid("geo.distance must be compared with a number.");
                }
                Object literal = constant.value();
                return frame -> compare(distance.evaluate(frame), operator, literal);
            }
            Predicate predicate = booleanCall(call, scope);
            if (!(constant.value() instanceof Boolean expected) || operator.isRange()) {
                throw invalid("The result of '" + call.name() + "' can only be compared with true or false using "
                    + "'eq' or 'ne'.");
            }
            boolean wanted = operator == ComparisonOperator.EQ ? expected : !expected;
            return frame -> predicate.test(frame) == wanted;
        }

        private Predicate lambda(Lambda lambda, Scope scope) {
            Reference collection = reference(lambda.collection(), scope);
            if (!collection.type().collection() && !collection.multiValued()) {
                throw invalid("'" + collection.text() + "' is not a collection; 'any' and 'all' apply only to "
                    + "collection fields.");
            }
            if (lambda.variable() == null) {
                return frame -> collection.evaluate(frame) instanceof List<?> list && !list.isEmpty();
            }
            SearchFieldDefinition field = collection.field();
            if (field.type().element() == EdmType.STRING) {
                checkStringCollectionLambda(lambda.body(), lambda.all(), lambda.variable());
            }
            Predicate body = bind(lambda.body(), scope.with(lambda.variable(), field));
            String variable = lambda.variable();
            boolean all = lambda.all();
            return frame -> {
                Object value = collection.evaluate(frame);
                List<?> elements = value instanceof List<?> list ? list : List.of();
                for (Object element : elements) {
                    boolean matched = body.test(frame.with(variable, element));
                    if (all && !matched) {
                        return false;
                    }
                    if (!all && matched) {
                        return true;
                    }
                }
                return all;
            };
        }

        private Predicate booleanCall(Operand.Call call, Scope scope) {
            return switch (call.name()) {
                case "search.in" -> searchIn(call, scope);
                case "search.ismatch", "search.ismatchscoring" -> isMatch(call, scope);
                case "geo.intersects" -> geoIntersects(call, scope);
                default -> throw invalid("Unknown function '" + call.name() + "'.");
            };
        }

        private Predicate searchIn(Operand.Call call, Scope scope) {
            List<Operand> arguments = call.arguments();
            if (arguments.size() < 2 || arguments.size() > 3 || !(arguments.get(0) instanceof Operand.Path path)
                    || !(arguments.get(1) instanceof Operand.Constant values) || !values.isString()
                    || (arguments.size() == 3 && !(arguments.get(2) instanceof Operand.Constant delimiters
                        && delimiters.isString()))) {
                throw invalid("search.in takes a field or range variable, a string of values and optionally a "
                    + "string of delimiters.");
            }
            Reference reference = reference(path, scope);
            requireSingleValued(reference);
            if (reference.type().element() != EdmType.STRING) {
                throw invalid("search.in applies only to string fields; '" + reference.text() + "' is "
                    + reference.type().edmName() + ".");
            }
            String delimiters = arguments.size() == 3
                ? (String) ((Operand.Constant) arguments.get(2)).value() : " ,";
            Optional<LexicalNormalizer> normalizer = analysis.normalizer(reference.field());
            Set<String> accepted = new HashSet<>();
            for (String value : split((String) values.value(), delimiters)) {
                accepted.add(normalizer.map(n -> n.normalize(value)).orElse(value));
            }
            return frame -> reference.evaluate(frame) instanceof String value && accepted.contains(value);
        }

        private Predicate isMatch(Operand.Call call, Scope scope) {
            if (scope.insideLambda()) {
                throw invalid("search.ismatch and search.ismatchscoring cannot be used inside a lambda "
                    + "expression.");
            }
            List<Operand> arguments = call.arguments();
            if (arguments.isEmpty() || arguments.size() == 3 || arguments.size() > 4) {
                throw invalid(call.name() + " takes the search text, and optionally the search fields, or the "
                    + "search fields, the query type and the search mode.");
            }
            List<String> strings = new ArrayList<>();
            for (Operand argument : arguments) {
                if (!(argument instanceof Operand.Constant constant) || !constant.isString()) {
                    throw invalid("The arguments of " + call.name() + " must be string literals.");
                }
                strings.add((String) constant.value());
            }
            List<String> fields = strings.size() > 1 ? csv(strings.get(1)) : List.of();
            for (String path : fields) {
                SearchFieldDefinition field = definition.field(path).orElseThrow(() -> invalid(
                    "Could not find a property named '" + path + "' on type 'search.document'."));
                if (!field.isFullTextSearchable()) {
                    throw invalid("The field '" + path + "' named in " + call.name() + " is not searchable.");
                }
            }
            QueryType queryType = strings.size() > 2 ? queryType(strings.get(2)) : QueryType.SIMPLE;
            SearchMode searchMode = strings.size() > 3 ? SearchMode.parse(strings.get(3)) : SearchMode.ANY;
            int id = calls.size();
            calls.add(new FullTextCall(id, strings.get(0), fields, queryType, searchMode,
                call.name().equals("search.ismatchscoring")));
            return frame -> frame.matches().matches(id, frame.document().ordinal());
        }

        private Predicate geoIntersects(Operand.Call call, Scope scope) {
            List<Operand> arguments = call.arguments();
            if (arguments.size() != 2 || !(arguments.get(0) instanceof Operand.Path path)
                    || !(arguments.get(1) instanceof Operand.PolygonConstant polygon)) {
                throw invalid("geo.intersects takes a geography point field or range variable and a polygon "
                    + "literal.");
            }
            Reference reference = geoReference(path, scope);
            List<GeoPoint> ring = polygon.ring();
            return frame -> reference.evaluate(frame) instanceof GeoPoint point && WellKnownText.contains(ring, point);
        }

        private Value geoDistance(Operand.Call call, Scope scope) {
            List<Operand> arguments = call.arguments();
            Operand.Path path = null;
            GeoPoint point = null;
            if (arguments.size() == 2) {
                for (Operand argument : arguments) {
                    if (argument instanceof Operand.Path candidate) {
                        path = candidate;
                    } else if (argument instanceof Operand.PointConstant constant) {
                        point = constant.point();
                    }
                }
            }
            if (path == null || point == null) {
                throw invalid("geo.distance takes a geography point field or range variable and a point literal.");
            }
            Reference reference = geoReference(path, scope);
            GeoPoint origin = point;
            return frame -> reference.evaluate(frame) instanceof GeoPoint target ? target.distanceKm(origin) : null;
        }

        private Reference geoReference(Operand.Path path, Scope scope) {
            Reference reference = reference(path, scope);
            requireSingleValued(reference);
            if (reference.type().element() != EdmType.GEOGRAPHY_POINT) {
                throw invalid("'" + reference.text() + "' is not an Edm.GeographyPoint field.");
            }
            return reference;
        }

        private Reference reference(Operand.Path path, Scope scope) {
            List<String> segments = path.segments();
            SearchFieldDefinition collection = scope.variables().get(segments.get(0));
            if (collection != null) {
                List<String> rest = segments.subList(1, segments.size());
                if (rest.isEmpty()) {
                    requireFilterable(collection, segments.get(0));
                    return new Reference(segments.get(0), List.of(), collection,
                        new FieldType(collection.type().element(), false), false);
                }
                SearchFieldDefinition current = collection;
                boolean multiValued = false;
                for (int index = 0; index < rest.size(); index++) {
                    if (index > 0 && current.type().collection()) {
                        multiValued = true;
                    }
                    String segment = rest.get(index);
                    current = current.child(segment).orElseThrow(() -> invalid("Could not find a property named '"
                        + segment + "' on the range variable '" + segments.get(0) + "'."));
                }
                requireFilterable(current, path.text());
                return new Reference(segments.get(0), List.copyOf(rest), current, current.type(),
                    multiValued || current.type().collection());
            }
            SearchFieldDefinition field = definition.field(path.text()).orElseThrow(() -> invalid(
                "Could not find a property named '" + path.text() + "' on type 'search.document'."));
            requireFilterable(field, path.text());
            return new Reference(null, segments, field, field.type(), field.multiValued());
        }

        private static void requireFilterable(SearchFieldDefinition field, String text) {
            if (!field.isComplex() && !field.filterable()) {
                throw invalid("The field '" + text + "' is not filterable. Only fields marked as filterable can be "
                    + "used in filter expressions.");
            }
        }

        private static void requireSingleValued(Reference reference) {
            if (reference.multiValued() || reference.type().collection()) {
                throw invalid("The operand '" + reference.text() + "' is not a single value. Use 'any' or 'all' to "
                    + "filter on a collection.");
            }
            if (reference.type().isComplex()) {
                throw invalid("The operand '" + reference.text() + "' is a complex field; filters compare its "
                    + "subfields.");
            }
        }

        private Object checkLiteral(Reference reference, ComparisonOperator operator, Operand.Constant constant) {
            EdmType type = reference.type().element();
            if (constant.isNull()) {
                if (operator.isRange()) {
                    throw invalid("null can only be compared with 'eq' or 'ne'.");
                }
                return null;
            }
            boolean compatible = switch (type) {
                case STRING -> constant.isString();
                case INT32, INT64 -> constant.isNumber() && (!(constant.value() instanceof Double number)
                    || Double.isFinite(number));
                case DOUBLE -> constant.isNumber();
                case BOOLEAN -> constant.value() instanceof Boolean && !operator.isRange();
                case DATE_TIME_OFFSET -> constant.isDateTime();
                default -> false;
            };
            if (!compatible) {
                throw invalid("The literal '" + constant.value() + "' cannot be compared with the field '"
                    + reference.text() + "' of type " + reference.type().edmName() + " using '"
                    + operator.name().toLowerCase(Locale.ROOT) + "'.");
            }
            if (type == EdmType.STRING) {
                Optional<LexicalNormalizer> normalizer = analysis.normalizer(reference.field());
                if (normalizer.isPresent()) {
                    return normalizer.get().normalize((String) constant.value());
                }
            }
            return constant.value();
        }

        /** Applies the documented limits on lambdas over {@code Collection(Edm.String)}. */
        private static void checkStringCollectionLambda(FilterExpression body, boolean all, String variable) {
            if (!(all ? allowedInAll(body, variable) : allowedInAny(body, variable))) {
                throw invalid(all
                    ? "Inside an 'all' lambda over a string collection, only 'ne' comparisons and "
                        + "'not search.in(...)' on the range variable, combined with 'and', are supported."
                    : "Inside an 'any' lambda over a string collection, only 'eq' comparisons and 'search.in(...)' on "
                        + "the range variable, combined with 'or', are supported.");
            }
        }

        private static boolean allowedInAny(FilterExpression expression, String variable) {
            return switch (expression) {
                case Or or -> allowedInAny(or.left(), variable) && allowedInAny(or.right(), variable);
                case Comparison comparison -> comparison.operator() == ComparisonOperator.EQ
                    && namesVariable(comparison, variable);
                case Condition condition -> isSearchInOn(condition.operand(), variable);
                default -> false;
            };
        }

        private static boolean allowedInAll(FilterExpression expression, String variable) {
            return switch (expression) {
                case And and -> allowedInAll(and.left(), variable) && allowedInAll(and.right(), variable);
                case Comparison comparison -> comparison.operator() == ComparisonOperator.NE
                    && namesVariable(comparison, variable);
                case Not not -> not.operand() instanceof Condition condition
                    && isSearchInOn(condition.operand(), variable);
                default -> false;
            };
        }

        private static boolean namesVariable(Comparison comparison, String variable) {
            return isVariable(comparison.left(), variable) || isVariable(comparison.right(), variable);
        }

        private static boolean isVariable(Operand operand, String variable) {
            return operand instanceof Operand.Path path && path.segments().size() == 1
                && path.segments().get(0).equals(variable);
        }

        private static boolean isSearchInOn(Operand operand, String variable) {
            return operand instanceof Operand.Call call && call.name().equals("search.in")
                && !call.arguments().isEmpty() && isVariable(call.arguments().get(0), variable);
        }

        private static boolean isConstant(Operand operand) {
            return operand instanceof Operand.Constant || operand instanceof Operand.PointConstant
                || operand instanceof Operand.PolygonConstant;
        }

        private static QueryType queryType(String value) {
            QueryType type = QueryType.parse(value);
            if (type == QueryType.SEMANTIC) {
                throw invalid("search.ismatch supports the query types 'simple' and 'full'.");
            }
            return type;
        }
    }

    /** Compares a typed field value with a literal under {@code operator}, with OData's null and NaN rules. */
    static boolean compare(Object value, ComparisonOperator operator, Object literal) {
        if (literal == null || value == null) {
            boolean equal = value == literal;
            return switch (operator) {
                case EQ -> equal;
                case NE -> !equal;
                default -> false;
            };
        }
        int comparison;
        if (value instanceof Number left && literal instanceof Number right) {
            if (left instanceof Long longLeft && right instanceof Long longRight) {
                comparison = Long.compare(longLeft, longRight);
            } else {
                double a = left.doubleValue();
                double b = right.doubleValue();
                if (Double.isNaN(a) || Double.isNaN(b)) {
                    return operator == ComparisonOperator.NE;
                }
                comparison = Double.compare(a == 0.0 ? 0.0 : a, b == 0.0 ? 0.0 : b);
            }
        } else if (value instanceof String left && literal instanceof String right) {
            comparison = left.compareTo(right);
        } else if (value instanceof Instant left && literal instanceof Instant right) {
            comparison = left.compareTo(right);
        } else if (value instanceof Boolean left && literal instanceof Boolean right) {
            comparison = Boolean.compare(left, right);
        } else {
            return operator == ComparisonOperator.NE;
        }
        return switch (operator) {
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
            case GT -> comparison > 0;
            case LT -> comparison < 0;
            case GE -> comparison >= 0;
            case LE -> comparison <= 0;
        };
    }

    private static List<String> split(String values, String delimiters) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char character : values.toCharArray()) {
            if (delimiters.indexOf(character) >= 0) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static List<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("Invalid expression: " + detail + "\r\nParameter name: $filter");
    }
}
