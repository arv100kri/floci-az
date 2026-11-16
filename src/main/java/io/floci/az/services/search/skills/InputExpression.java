package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A skill input written as an expression of the skill annotation language: a source that starts with {@code =}, such
 * as {@code = $(/document/language) == 'en'}. It supports what the language documents: number, string, Boolean and
 * {@code null} literals, paths in {@code $(...)}, inline arrays ({@code =['item', $(/document/x)]}), unary {@code !}
 * and {@code -}, {@code * / %}, {@code + -} (with {@code +} also joining strings), the comparisons
 * {@code < <= > >=}, {@code == !=}, the logical {@code && ^ ||}, the ternary {@code ?:} and parentheses, with the usual
 * priorities. Joining strings with {@code +} puts one space between them, as the documented examples show
 * ({@code ="Hello," + "world!"} is {@code "Hello, world!"}).
 */
final class InputExpression {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String text;
    private final Node root;

    private InputExpression(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    /** Whether {@code source} is an expression rather than a path. */
    static boolean isExpression(String source) {
        return source != null && source.stripLeading().startsWith("=");
    }

    /**
     * Parses {@code source}, the input source including its leading {@code =}.
     *
     * @throws IllegalArgumentException when the expression is not valid
     */
    static InputExpression parse(String source) {
        String body = source.stripLeading().substring(1);
        Parser parser = new Parser(body);
        Node root = parser.expression();
        parser.expectEnd();
        return new InputExpression(source, root);
    }

    /** The source text, {@code =} included. */
    String text() {
        return text;
    }

    /** Every path the expression reads. */
    List<EnrichmentPath> paths() {
        List<EnrichmentPath> paths = new ArrayList<>();
        collectPaths(root, paths);
        return paths;
    }

    private static void collectPaths(Node node, List<EnrichmentPath> paths) {
        switch (node) {
            case PathRef reference -> paths.add(reference.path());
            case ArrayOf array -> array.items().forEach(item -> collectPaths(item, paths));
            case Unary unary -> collectPaths(unary.operand(), paths);
            case Binary binary -> {
                collectPaths(binary.left(), paths);
                collectPaths(binary.right(), paths);
            }
            case Conditional conditional -> {
                collectPaths(conditional.test(), paths);
                collectPaths(conditional.whenTrue(), paths);
                collectPaths(conditional.whenFalse(), paths);
            }
            case Literal literal -> {
                // A literal reads no path.
            }
        }
    }

    /**
     * Evaluates the expression; {@code resolver} gives each path's value, null when the path selects nothing (which
     * the expression sees as {@code null}).
     *
     * @throws EvaluationException when an operator gets operands of the wrong type
     */
    JsonNode evaluate(Function<EnrichmentPath, JsonNode> resolver) {
        return evaluate(root, resolver);
    }

    private static JsonNode evaluate(Node node, Function<EnrichmentPath, JsonNode> resolver) {
        return switch (node) {
            case Literal literal -> literal.value();
            case PathRef reference -> {
                JsonNode value = resolver.apply(reference.path());
                yield value == null || value.isMissingNode() ? NODES.nullNode() : value;
            }
            case ArrayOf array -> {
                ArrayNode values = NODES.arrayNode();
                for (Node item : array.items()) {
                    values.add(evaluate(item, resolver));
                }
                yield values;
            }
            case Unary unary -> unary(unary.operator(), evaluate(unary.operand(), resolver));
            case Binary binary -> binary(binary, resolver);
            case Conditional conditional -> {
                JsonNode test = evaluate(conditional.test(), resolver);
                if (!test.isBoolean()) {
                    throw new EvaluationException("the condition of '?:' is not a Boolean value");
                }
                yield evaluate(test.booleanValue() ? conditional.whenTrue() : conditional.whenFalse(), resolver);
            }
        };
    }

    private static JsonNode unary(char operator, JsonNode operand) {
        if (operator == '!') {
            if (!operand.isBoolean()) {
                throw new EvaluationException("operator '!' needs a Boolean value");
            }
            return NODES.booleanNode(!operand.booleanValue());
        }
        if (!operand.isNumber()) {
            throw new EvaluationException("operator '-' needs a number");
        }
        if (operand.isIntegralNumber() && operand.canConvertToLong() && operand.longValue() != Long.MIN_VALUE) {
            return NODES.numberNode(-operand.longValue());
        }
        return NODES.numberNode(-operand.doubleValue());
    }

    private static JsonNode binary(Binary binary, Function<EnrichmentPath, JsonNode> resolver) {
        String operator = binary.operator();
        JsonNode left = evaluate(binary.left(), resolver);
        if (operator.equals("&&") || operator.equals("||")) {
            requireBoolean(operator, left);
            if (operator.equals("&&") && !left.booleanValue()) {
                return NODES.booleanNode(false);
            }
            if (operator.equals("||") && left.booleanValue()) {
                return NODES.booleanNode(true);
            }
            JsonNode right = evaluate(binary.right(), resolver);
            requireBoolean(operator, right);
            return NODES.booleanNode(right.booleanValue());
        }
        JsonNode right = evaluate(binary.right(), resolver);
        return switch (operator) {
            case "^" -> {
                requireBoolean(operator, left);
                requireBoolean(operator, right);
                yield NODES.booleanNode(left.booleanValue() ^ right.booleanValue());
            }
            case "==" -> NODES.booleanNode(equal(left, right));
            case "!=" -> NODES.booleanNode(!equal(left, right));
            case "<", "<=", ">", ">=" -> NODES.booleanNode(compare(operator, left, right));
            case "+" -> left.isTextual() || right.isTextual() ? join(left, right) : arithmetic(operator, left, right);
            default -> arithmetic(operator, left, right);
        };
    }

    private static void requireBoolean(String operator, JsonNode value) {
        if (!value.isBoolean()) {
            throw new EvaluationException("operator '" + operator + "' needs Boolean values");
        }
    }

    private static boolean equal(JsonNode left, JsonNode right) {
        if (left.isNull() || right.isNull()) {
            return left.isNull() && right.isNull();
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private static boolean compare(String operator, JsonNode left, JsonNode right) {
        int order;
        if (left.isNumber() && right.isNumber()) {
            order = left.decimalValue().compareTo(right.decimalValue());
        } else if (left.isTextual() && right.isTextual()) {
            order = left.textValue().compareTo(right.textValue());
        } else {
            return false;
        }
        return switch (operator) {
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            case ">" -> order > 0;
            default -> order >= 0;
        };
    }

    private static JsonNode join(JsonNode left, JsonNode right) {
        if (left.isNull()) {
            return NODES.textNode(asText(right));
        }
        if (right.isNull()) {
            return NODES.textNode(asText(left));
        }
        return NODES.textNode(asText(left) + " " + asText(right));
    }

    private static String asText(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static JsonNode arithmetic(String operator, JsonNode left, JsonNode right) {
        if (!left.isNumber() || !right.isNumber()) {
            throw new EvaluationException("operator '" + operator + "' needs numbers");
        }
        boolean integral = left.isIntegralNumber() && right.isIntegralNumber() && left.canConvertToLong()
            && right.canConvertToLong();
        if (integral) {
            long a = left.longValue();
            long b = right.longValue();
            try {
                return switch (operator) {
                    case "+" -> NODES.numberNode(Math.addExact(a, b));
                    case "-" -> NODES.numberNode(Math.subtractExact(a, b));
                    case "*" -> NODES.numberNode(Math.multiplyExact(a, b));
                    case "%" -> {
                        if (b == 0) {
                            throw new EvaluationException("division by zero");
                        }
                        yield NODES.numberNode(a % b);
                    }
                    default -> {
                        if (b == 0) {
                            throw new EvaluationException("division by zero");
                        }
                        yield a % b == 0 ? NODES.numberNode(a / b) : NODES.numberNode((double) a / b);
                    }
                };
            } catch (ArithmeticException overflow) {
                return doubleArithmetic(operator, left.doubleValue(), right.doubleValue());
            }
        }
        return doubleArithmetic(operator, left.doubleValue(), right.doubleValue());
    }

    private static JsonNode doubleArithmetic(String operator, double a, double b) {
        double result = switch (operator) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "%" -> a % b;
            default -> a / b;
        };
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new EvaluationException("operator '" + operator + "' has no finite result");
        }
        return NODES.numberNode(result);
    }

    /** An operator applied to values of the wrong type, or another evaluation failure. */
    static final class EvaluationException extends RuntimeException {

        EvaluationException(String message) {
            super(message);
        }
    }

    private sealed interface Node permits Literal, PathRef, ArrayOf, Unary, Binary, Conditional {
    }

    private record Literal(JsonNode value) implements Node {
    }

    private record PathRef(EnrichmentPath path) implements Node {
    }

    private record ArrayOf(List<Node> items) implements Node {
    }

    private record Unary(char operator, Node operand) implements Node {
    }

    private record Binary(String operator, Node left, Node right) implements Node {
    }

    private record Conditional(Node test, Node whenTrue, Node whenFalse) implements Node {
    }

    /** A recursive-descent parser over the expression text, one method per priority level. */
    private static final class Parser {

        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Node expression() {
            Node test = logicalOr();
            if (consume("?")) {
                Node whenTrue = expression();
                expect(":");
                Node whenFalse = expression();
                return new Conditional(test, whenTrue, whenFalse);
            }
            return test;
        }

        private Node logicalOr() {
            Node node = logicalXor();
            while (consume("||")) {
                node = new Binary("||", node, logicalXor());
            }
            return node;
        }

        private Node logicalXor() {
            Node node = logicalAnd();
            while (consume("^")) {
                node = new Binary("^", node, logicalAnd());
            }
            return node;
        }

        private Node logicalAnd() {
            Node node = equality();
            while (consume("&&")) {
                node = new Binary("&&", node, equality());
            }
            return node;
        }

        private Node equality() {
            Node node = comparison();
            while (true) {
                if (consume("==")) {
                    node = new Binary("==", node, comparison());
                } else if (consume("!=")) {
                    node = new Binary("!=", node, comparison());
                } else {
                    return node;
                }
            }
        }

        private Node comparison() {
            Node node = additive();
            while (true) {
                if (consume("<=")) {
                    node = new Binary("<=", node, additive());
                } else if (consume(">=")) {
                    node = new Binary(">=", node, additive());
                } else if (consume("<")) {
                    node = new Binary("<", node, additive());
                } else if (consume(">")) {
                    node = new Binary(">", node, additive());
                } else {
                    return node;
                }
            }
        }

        private Node additive() {
            Node node = multiplicative();
            while (true) {
                if (consume("+")) {
                    node = new Binary("+", node, multiplicative());
                } else if (consume("-")) {
                    node = new Binary("-", node, multiplicative());
                } else {
                    return node;
                }
            }
        }

        private Node multiplicative() {
            Node node = unary();
            while (true) {
                if (consume("*")) {
                    node = new Binary("*", node, unary());
                } else if (consume("/")) {
                    node = new Binary("/", node, unary());
                } else if (consume("%")) {
                    node = new Binary("%", node, unary());
                } else {
                    return node;
                }
            }
        }

        private Node unary() {
            skipWhitespace();
            if (peek() == '!' && peekAt(1) != '=') {
                position++;
                return new Unary('!', unary());
            }
            if (consume("-")) {
                return new Unary('-', unary());
            }
            return primary();
        }

        private Node primary() {
            skipWhitespace();
            if (position >= text.length()) {
                throw error("the expression ends where a value was expected");
            }
            char current = text.charAt(position);
            if (consume("$(")) {
                int close = text.indexOf(')', position);
                if (close < 0) {
                    throw error("a path in '$(' is not closed with ')'");
                }
                String path = text.substring(position, close);
                position = close + 1;
                return new PathRef(EnrichmentPath.parse(path));
            }
            if (consume("(")) {
                Node inner = expression();
                expect(")");
                return inner;
            }
            if (consume("[")) {
                List<Node> items = new ArrayList<>();
                if (!consume("]")) {
                    do {
                        items.add(expression());
                    } while (consume(","));
                    expect("]");
                }
                return new ArrayOf(List.copyOf(items));
            }
            if (current == '\'' || current == '"') {
                return new Literal(NODES.textNode(string(current)));
            }
            if (Character.isDigit(current) || current == '.') {
                return new Literal(number());
            }
            if (Character.isLetter(current)) {
                int start = position;
                while (position < text.length() && Character.isLetterOrDigit(text.charAt(position))) {
                    position++;
                }
                String word = text.substring(start, position);
                return switch (word) {
                    case "true" -> new Literal(NODES.booleanNode(true));
                    case "false" -> new Literal(NODES.booleanNode(false));
                    case "null" -> new Literal(NODES.nullNode());
                    default -> throw error("'" + word + "' is not a value; put strings in quotation marks and "
                        + "paths in $(...)");
                };
            }
            throw error("unexpected character '" + current + "'");
        }

        private String string(char quote) {
            position++;
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == quote) {
                    return value.toString();
                }
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (position >= text.length()) {
                    break;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("an incomplete \\u escape");
                        }
                        try {
                            value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("an invalid \\u escape");
                        }
                        position += 4;
                    }
                    default -> value.append(escaped);
                }
            }
            throw error("a string is not closed with " + quote);
        }

        private JsonNode number() {
            int start = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))) {
                position++;
            }
            boolean decimal = false;
            if (position < text.length() && text.charAt(position) == '.') {
                decimal = true;
                position++;
                while (position < text.length() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }
            if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                    position++;
                }
                while (position < text.length() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }
            String literal = text.substring(start, position);
            try {
                if (!decimal) {
                    BigDecimal whole = new BigDecimal(literal);
                    return whole.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                        ? NODES.numberNode(whole.longValueExact()) : NODES.numberNode(whole.doubleValue());
                }
                return NODES.numberNode(Double.parseDouble(literal));
            } catch (NumberFormatException | ArithmeticException e) {
                throw error("'" + literal + "' is not a number");
            }
        }

        private boolean consume(String token) {
            skipWhitespace();
            if (text.startsWith(token, position)) {
                position += token.length();
                return true;
            }
            return false;
        }

        private void expect(String token) {
            if (!consume(token)) {
                throw error("'" + token + "' was expected");
            }
        }

        void expectEnd() {
            skipWhitespace();
            if (position < text.length()) {
                throw error("unexpected text '" + text.substring(position) + "'");
            }
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private char peek() {
            return position < text.length() ? text.charAt(position) : 0;
        }

        private char peekAt(int offset) {
            return position + offset < text.length() ? text.charAt(position + offset) : 0;
        }

        private IllegalArgumentException error(String detail) {
            return new IllegalArgumentException("the expression '=" + text + "' is invalid: " + detail);
        }
    }
}
