package io.floci.az.services.search.engine.odata;

import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.GeoPoint;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.odata.FilterExpression.Operand;
import io.floci.az.services.search.model.EdmType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * A {@code $orderby} expression bound to an index, per Learn's "OData $orderby syntax": up to 32 clauses, each a
 * sortable field, {@code search.score()} or {@code geo.distance(field, point)}, ascending unless {@code desc}. Nulls
 * sort first ascending and last descending; a null geography point is the farthest. Strings sort ordinally (after the
 * field's normalizer). Immutable.
 */
public final class ODataOrderBy {

    private static final String PARAMETER = "$orderby";
    private static final int MAX_CLAUSES = 32;

    /** What a clause sorts by. */
    public enum Target {
        FIELD, SCORE, GEO_DISTANCE
    }

    /**
     * One sort criterion.
     *
     * @param target     what it sorts by
     * @param field      the field (null for {@code search.score()})
     * @param origin     the point distances are measured from, for {@code geo.distance}
     * @param descending whether the order is descending
     */
    public record Clause(Target target, SearchFieldDefinition field, GeoPoint origin, boolean descending) {
    }

    private final List<Clause> clauses;

    private ODataOrderBy(List<Clause> clauses) {
        this.clauses = List.copyOf(clauses);
    }

    /** Parses and binds {@code text}; null or blank means no criteria. */
    public static ODataOrderBy parse(String text, SearchIndexDefinition definition) {
        if (text == null || text.isBlank()) {
            return new ODataOrderBy(List.of());
        }
        FilterParser parser = new FilterParser(ODataLexer.tokenize(text, PARAMETER), PARAMETER);
        List<Clause> clauses = new ArrayList<>();
        do {
            if (parser.peek().kind() == ODataLexer.Kind.END) {
                break;
            }
            Object operand = parser.operandOrLambda();
            boolean descending = false;
            if (parser.peek().isKeyword("desc")) {
                parser.next();
                descending = true;
            } else if (parser.peek().isKeyword("asc")) {
                parser.next();
            }
            clauses.add(clause(operand, descending, definition));
        } while (parser.accept(ODataLexer.Kind.COMMA));
        parser.expect(ODataLexer.Kind.END, "',' or the end of the expression");
        if (clauses.size() > MAX_CLAUSES) {
            throw invalid("$orderby has " + clauses.size() + " clauses; the maximum is " + MAX_CLAUSES + ".");
        }
        return new ODataOrderBy(clauses);
    }

    private static Clause clause(Object operand, boolean descending, SearchIndexDefinition definition) {
        if (operand instanceof Operand.Path path) {
            return new Clause(Target.FIELD, sortableField(path, definition), null, descending);
        }
        if (operand instanceof Operand.Call call) {
            if (call.name().equals("search.score") && call.arguments().isEmpty()) {
                return new Clause(Target.SCORE, null, null, descending);
            }
            if (call.name().equals("geo.distance") && call.arguments().size() == 2) {
                Operand.Path path = null;
                GeoPoint origin = null;
                for (Operand argument : call.arguments()) {
                    if (argument instanceof Operand.Path candidate) {
                        path = candidate;
                    } else if (argument instanceof Operand.PointConstant point) {
                        origin = point.point();
                    }
                }
                if (path != null && origin != null) {
                    SearchFieldDefinition field = sortableField(path, definition);
                    if (field.type().element() != EdmType.GEOGRAPHY_POINT) {
                        throw invalid("geo.distance in $orderby needs an Edm.GeographyPoint field; '" + field.path()
                            + "' is " + field.type().edmName() + ".");
                    }
                    return new Clause(Target.GEO_DISTANCE, field, origin, descending);
                }
            }
            throw invalid("Only search.score() and geo.distance(field, geography'POINT(lon lat)') can be used in "
                + "$orderby.");
        }
        throw invalid("$orderby clauses must be field paths, search.score() or geo.distance(...).");
    }

    private static SearchFieldDefinition sortableField(Operand.Path path, SearchIndexDefinition definition) {
        String text = String.join("/", path.segments());
        SearchFieldDefinition field = definition.field(text).orElseThrow(() -> invalid(
            "Could not find a property named '" + text + "' on type 'search.document'."));
        if (!field.sortable() || field.multiValued() || field.isComplex()) {
            throw invalid("The field '" + text + "' is not sortable. Only single-valued fields marked as sortable "
                + "can be used in $orderby.");
        }
        return field;
    }

    public List<Clause> clauses() {
        return clauses;
    }

    public boolean isEmpty() {
        return clauses.isEmpty();
    }

    /** Whether any clause sorts by score, so ties need no extra score ordering. */
    public boolean sortsByScore() {
        return clauses.stream().anyMatch(clause -> clause.target() == Target.SCORE);
    }

    /**
     * Orders items by the clauses. The caller appends its own tie-breakers (score, then a stable order).
     *
     * @param document the document of an item
     * @param score    the score of an item
     */
    public <T> Comparator<T> comparator(Function<T, IndexedDocument> document, ToDoubleFunction<T> score) {
        Comparator<T> comparator = (left, right) -> 0;
        for (Clause clause : clauses) {
            Comparator<T> criterion = switch (clause.target()) {
                case SCORE -> Comparator.comparingDouble(score);
                case GEO_DISTANCE -> Comparator.comparingDouble(item -> distance(document.apply(item), clause));
                case FIELD -> (left, right) -> compareValues(value(document.apply(left), clause),
                    value(document.apply(right), clause));
            };
            comparator = comparator.thenComparing(clause.descending() ? criterion.reversed() : criterion);
        }
        return comparator;
    }

    private static Object value(IndexedDocument document, Clause clause) {
        return DocumentValues.valueAt(document.values(), List.of(clause.field().path().split("/")));
    }

    private static double distance(IndexedDocument document, Clause clause) {
        return value(document, clause) instanceof GeoPoint point ? point.distanceKm(clause.origin())
            : Double.POSITIVE_INFINITY;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object left, Object right) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : -1) : 1;
        }
        if (left instanceof Comparable comparable && left.getClass() == right.getClass()) {
            return comparable.compareTo(right);
        }
        return left.toString().compareTo(right.toString());
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("Invalid expression: " + detail + "\r\nParameter name: " + PARAMETER);
    }
}
