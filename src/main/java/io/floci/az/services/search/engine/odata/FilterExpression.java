package io.floci.az.services.search.engine.odata;

import io.floci.az.services.search.engine.index.GeoPoint;

import java.time.Instant;
import java.util.List;

/** The syntax tree of a {@code $filter} expression, before it is bound to an index. */
public sealed interface FilterExpression {

    /** {@code left and right}. */
    record And(FilterExpression left, FilterExpression right) implements FilterExpression {
    }

    /** {@code left or right}. */
    record Or(FilterExpression left, FilterExpression right) implements FilterExpression {
    }

    /** {@code not operand}. */
    record Not(FilterExpression operand) implements FilterExpression {
    }

    /** {@code left op right}, one side a constant, the other a variable or function call. */
    record Comparison(Operand left, ComparisonOperator operator, Operand right) implements FilterExpression {
    }

    /** {@code true} or {@code false}. */
    record BooleanConstant(boolean value) implements FilterExpression {
    }

    /** A boolean field, range variable or function call used as a condition by itself. */
    record Condition(Operand operand) implements FilterExpression {
    }

    /**
     * {@code collection/any(variable: body)}, {@code collection/all(variable: body)} or {@code collection/any()}
     * (null variable and body).
     */
    record Lambda(Operand.Path collection, boolean all, String variable, FilterExpression body)
        implements FilterExpression {
    }

    /** The comparison operators. */
    enum ComparisonOperator {
        EQ, NE, GT, LT, GE, LE;

        /** The operator with its operands swapped: {@code 5 lt Rating} is {@code Rating gt 5}. */
        ComparisonOperator mirrored() {
            return switch (this) {
                case GT -> LT;
                case LT -> GT;
                case GE -> LE;
                case LE -> GE;
                default -> this;
            };
        }

        boolean isRange() {
            return this != EQ && this != NE;
        }
    }

    /** What a comparison compares, or a function argument. */
    sealed interface Operand {

        /** A field path, or a range variable followed by a sub-path. */
        record Path(List<String> segments) implements Operand {

            public Path {
                segments = List.copyOf(segments);
            }

            String text() {
                return String.join("/", segments);
            }
        }

        /** A function call: {@code geo.distance}, {@code geo.intersects}, {@code search.in}, ... */
        record Call(String name, List<Operand> arguments) implements Operand {

            public Call {
                arguments = List.copyOf(arguments);
            }
        }

        /** A constant: {@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@code Instant}, or null. */
        record Constant(Object value) implements Operand {

            boolean isNull() {
                return value == null;
            }

            boolean isNumber() {
                return value instanceof Long || value instanceof Double;
            }

            boolean isString() {
                return value instanceof String;
            }

            boolean isDateTime() {
                return value instanceof Instant;
            }
        }

        /** {@code geography'POINT(lon lat)'}. */
        record PointConstant(GeoPoint point) implements Operand {
        }

        /** {@code geography'POLYGON((lon lat, ...))'}, closed, at least four points. */
        record PolygonConstant(List<GeoPoint> ring) implements Operand {

            public PolygonConstant {
                ring = List.copyOf(ring);
            }
        }
    }
}
