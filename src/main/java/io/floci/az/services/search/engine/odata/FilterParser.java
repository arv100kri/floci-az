package io.floci.az.services.search.engine.odata;

import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.engine.odata.FilterExpression.And;
import io.floci.az.services.search.engine.odata.FilterExpression.BooleanConstant;
import io.floci.az.services.search.engine.odata.FilterExpression.Comparison;
import io.floci.az.services.search.engine.odata.FilterExpression.ComparisonOperator;
import io.floci.az.services.search.engine.odata.FilterExpression.Condition;
import io.floci.az.services.search.engine.odata.FilterExpression.Lambda;
import io.floci.az.services.search.engine.odata.FilterExpression.Not;
import io.floci.az.services.search.engine.odata.FilterExpression.Operand;
import io.floci.az.services.search.engine.odata.FilterExpression.Or;
import io.floci.az.services.search.engine.odata.ODataLexer.Kind;
import io.floci.az.services.search.engine.odata.ODataLexer.Token;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses {@code $filter} expressions per the EBNF of Learn's "OData expression syntax reference": {@code and},
 * {@code or} and {@code not}; comparisons with a constant on either side; {@code any}/{@code all} lambdas; and the
 * functions {@code search.in}, {@code search.ismatch}, {@code search.ismatchscoring}, {@code geo.distance} and
 * {@code geo.intersects}. {@code not} binds tighter than {@code and}, which binds tighter than {@code or}. Operator
 * keywords are matched ignoring case.
 */
final class FilterParser {

    static final String FILTER_PARAMETER = "$filter";

    private final List<Token> tokens;
    private final String parameter;
    private int index;

    FilterParser(List<Token> tokens, String parameter) {
        this.tokens = tokens;
        this.parameter = parameter;
    }

    static FilterExpression parse(String text) {
        FilterParser parser = new FilterParser(ODataLexer.tokenize(text, FILTER_PARAMETER), FILTER_PARAMETER);
        FilterExpression expression = parser.expression();
        parser.expect(Kind.END, "end of expression");
        return expression;
    }

    FilterExpression expression() {
        FilterExpression left = conjunction();
        while (peek().isKeyword("or")) {
            index++;
            left = new Or(left, conjunction());
        }
        return left;
    }

    private FilterExpression conjunction() {
        FilterExpression left = unary();
        while (peek().isKeyword("and")) {
            index++;
            left = new And(left, unary());
        }
        return left;
    }

    private FilterExpression unary() {
        if (peek().isKeyword("not")) {
            index++;
            return new Not(unary());
        }
        return primary();
    }

    private FilterExpression primary() {
        if (peek().kind() == Kind.OPEN) {
            index++;
            FilterExpression inner = expression();
            expect(Kind.CLOSE, "')'");
            return inner;
        }
        Object first = operandOrLambda();
        if (first instanceof Lambda lambda) {
            return lambda;
        }
        Operand left = (Operand) first;
        ComparisonOperator operator = comparisonOperator(peek());
        if (operator != null) {
            index++;
            Object second = operandOrLambda();
            if (!(second instanceof Operand right)) {
                throw ODataLexer.syntax(parameter, "a lambda expression cannot be compared");
            }
            return new Comparison(left, operator, right);
        }
        if (left instanceof Operand.Constant constant) {
            if (constant.value() instanceof Boolean value) {
                return new BooleanConstant(value);
            }
            throw ODataLexer.syntax(parameter, "expected a boolean expression at position " + previous().position());
        }
        return new Condition(left);
    }

    /** An operand, or a lambda when a path ends in {@code /any(...)} or {@code /all(...)}. */
    Object operandOrLambda() {
        Token token = next();
        return switch (token.kind()) {
            case STRING -> new Operand.Constant(token.text());
            case INTEGER -> integer(token.text());
            case FLOAT -> new Operand.Constant(floating(token.text()));
            case DATE_TIME -> new Operand.Constant(dateTime(token));
            case GEOGRAPHY -> WellKnownText.parse(token.text(), parameter);
            case IDENTIFIER -> identifier(token);
            default -> throw ODataLexer.syntax(parameter, "unexpected '" + token.text() + "' at position "
                + token.position());
        };
    }

    private Object identifier(Token token) {
        String text = token.text();
        if (text.equalsIgnoreCase("null")) {
            return new Operand.Constant(null);
        }
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return new Operand.Constant(Boolean.parseBoolean(text.toLowerCase(Locale.ROOT)));
        }
        if (text.contains(".") && peek().kind() == Kind.OPEN) {
            index++;
            List<Operand> arguments = new ArrayList<>();
            if (peek().kind() != Kind.CLOSE) {
                do {
                    Object argument = operandOrLambda();
                    if (!(argument instanceof Operand operand)) {
                        throw ODataLexer.syntax(parameter, "a lambda expression cannot be a function argument");
                    }
                    arguments.add(operand);
                } while (accept(Kind.COMMA));
            }
            expect(Kind.CLOSE, "')'");
            return new Operand.Call(text.toLowerCase(Locale.ROOT), arguments);
        }
        List<String> segments = new ArrayList<>();
        segments.add(text);
        while (peek().kind() == Kind.SLASH) {
            index++;
            Token segment = expect(Kind.IDENTIFIER, "a field name");
            boolean any = segment.isKeyword("any");
            boolean all = segment.isKeyword("all");
            if ((any || all) && peek().kind() == Kind.OPEN) {
                return lambda(new Operand.Path(segments), all);
            }
            segments.add(segment.text());
        }
        return new Operand.Path(segments);
    }

    private Lambda lambda(Operand.Path collection, boolean all) {
        expect(Kind.OPEN, "'('");
        if (accept(Kind.CLOSE)) {
            if (all) {
                throw ODataLexer.syntax(parameter, "'all' requires a lambda expression");
            }
            return new Lambda(collection, false, null, null);
        }
        String variable = expect(Kind.IDENTIFIER, "a range variable").text();
        expect(Kind.COLON, "':'");
        FilterExpression body = expression();
        expect(Kind.CLOSE, "')'");
        return new Lambda(collection, all, variable, body);
    }

    private Operand.Constant integer(String text) {
        try {
            return new Operand.Constant(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return new Operand.Constant(Double.parseDouble(text));
        }
    }

    private static Double floating(String text) {
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "INF" -> Double.POSITIVE_INFINITY;
            case "-INF" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(text);
        };
    }

    private Instant dateTime(Token token) {
        Instant instant = DocumentCoercer.parseDateTime(token.text());
        if (instant == null) {
            throw ODataLexer.syntax(parameter, "'" + token.text() + "' is not a valid Edm.DateTimeOffset literal");
        }
        return instant;
    }

    private static ComparisonOperator comparisonOperator(Token token) {
        if (token.kind() != Kind.IDENTIFIER) {
            return null;
        }
        return switch (token.text().toLowerCase(Locale.ROOT)) {
            case "eq" -> ComparisonOperator.EQ;
            case "ne" -> ComparisonOperator.NE;
            case "gt" -> ComparisonOperator.GT;
            case "lt" -> ComparisonOperator.LT;
            case "ge" -> ComparisonOperator.GE;
            case "le" -> ComparisonOperator.LE;
            default -> null;
        };
    }

    Token peek() {
        return tokens.get(index);
    }

    private Token previous() {
        return tokens.get(Math.max(0, index - 1));
    }

    Token next() {
        Token token = tokens.get(index);
        if (token.kind() != Kind.END) {
            index++;
        }
        return token;
    }

    boolean accept(Kind kind) {
        if (peek().kind() == kind) {
            index++;
            return true;
        }
        return false;
    }

    Token expect(Kind kind, String what) {
        Token token = peek();
        if (token.kind() != kind) {
            throw ODataLexer.syntax(parameter, "expected " + what + " at position " + token.position()
                + (token.kind() == Kind.END ? " (end of expression)" : ", found '" + token.text() + "'"));
        }
        index++;
        return token;
    }
}
