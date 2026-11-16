package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.model.SearchException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A regular expression in the syntax of Lucene's {@code RegExp} class, which Learn's "Lucene query syntax" page names
 * for regular expression search ({@code /[mh]otel/}), matched against whole index terms. The grammar is the documented
 * one, with every optional production enabled:
 * <pre>
 *   union        := inter ('|' union)?
 *   inter        := concat ('&amp;' inter)?
 *   concat       := repeat+
 *   repeat       := complement ('?' | '*' | '+' | '{n}' | '{n,}' | '{n,m}')*
 *   complement   := '~' complement | '[' '^'? class+ ']' | simple
 *   class        := char ('-' char)?
 *   simple       := char | '.' | '#' | '@' | '"' string '"' | '(' ')' | '(' union ')' | '&lt;' n '-' m '&gt;'
 *   char         := a non-reserved character | '\' any character
 * </pre>
 * {@code .} is any character, {@code #} the empty language, {@code @} any string, {@code ~} the complement and
 * {@code &amp;} the intersection; {@code <n-m>} matches decimal numbers from n to m, of exactly their length when both
 * bounds have the same number of digits and with optional leading zeros otherwise. Named automata ({@code <name>})
 * are refused, since no automaton provider exists. Reserved characters must be escaped, inside character classes too.
 *
 * <p>Matching uses Brzozowski derivatives over interned expression nodes, memoised per node and character: in effect a
 * DFA built lazily, so complement and intersection cost nothing extra. Instances are not thread-safe; compile one per
 * query.</p>
 */
public final class LuceneRegExp {

    private static final String RESERVED = "|&?*+{~[.#@\"(<\\";
    private static final int MAX_STATES = 20_000;
    private static final int MAX_REPEAT = 1_000;

    private final Nodes nodes;
    private final Node root;

    private LuceneRegExp(Nodes nodes, Node root) {
        this.nodes = nodes;
        this.root = root;
    }

    /** Compiles {@code pattern}; a syntax error is a 400 naming the position. */
    public static LuceneRegExp compile(String pattern) {
        Nodes nodes = new Nodes();
        return new LuceneRegExp(nodes, new Parser(pattern, nodes).parse());
    }

    /**
     * Compiles a wildcard pattern: {@code *} matches any sequence of characters, {@code ?} any one character, and a
     * backslash makes the next character literal.
     */
    public static LuceneRegExp wildcard(String pattern) {
        Nodes nodes = new Nodes();
        List<Node> parts = new ArrayList<>();
        int index = 0;
        while (index < pattern.length()) {
            int codePoint = pattern.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\\' && index < pattern.length()) {
                int escaped = pattern.codePointAt(index);
                index += Character.charCount(escaped);
                parts.add(nodes.literal(escaped));
            } else if (codePoint == '*') {
                parts.add(nodes.anyString());
            } else if (codePoint == '?') {
                parts.add(nodes.anyChar());
            } else {
                parts.add(nodes.literal(codePoint));
            }
        }
        return new LuceneRegExp(nodes, nodes.sequence(parts));
    }

    /** Whether the whole of {@code term} matches. */
    public boolean matches(String term) {
        Node state = root;
        int index = 0;
        while (index < term.length()) {
            int codePoint = term.codePointAt(index);
            index += Character.charCount(codePoint);
            state = nodes.derivative(state, codePoint);
            if (state.kind == Kind.NOTHING) {
                return false;
            }
        }
        return state.nullable;
    }

    private enum Kind {
        NOTHING, EPSILON, CHARS, CONCAT, UNION, INTERSECTION, COMPLEMENT, STAR
    }

    /** An interned expression node: structurally equal nodes are the same object, so identity is equality. */
    private static final class Node {
        final int id;
        final Kind kind;
        final Node first;
        final Node second;
        final List<Node> members;
        final int[] ranges;
        final boolean nullable;

        Node(int id, Kind kind, Node first, Node second, List<Node> members, int[] ranges, boolean nullable) {
            this.id = id;
            this.kind = kind;
            this.first = first;
            this.second = second;
            this.members = members;
            this.ranges = ranges;
            this.nullable = nullable;
        }

        boolean contains(int codePoint) {
            for (int index = 0; index < ranges.length; index += 2) {
                if (codePoint >= ranges[index] && codePoint <= ranges[index + 1]) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The interning key of a node: its kind and the ids or ranges it is made of. */
    private record Key(Kind kind, int first, int second, List<Integer> members, List<Integer> ranges) {
    }

    /** Creates, simplifies and interns nodes, and memoises derivatives. */
    private static final class Nodes {
        private final Map<Key, Node> interned = new HashMap<>();
        private final Map<Long, Node> derivatives = new HashMap<>();
        private final Node nothing;
        private final Node epsilon;
        private final Node anyChar;
        private final Node anyString;

        Nodes() {
            nothing = intern(new Key(Kind.NOTHING, -1, -1, List.of(), List.of()), null, null, List.of(), new int[0],
                false);
            epsilon = intern(new Key(Kind.EPSILON, -1, -1, List.of(), List.of()), null, null, List.of(), new int[0],
                true);
            anyChar = chars(new int[] {0, Character.MAX_CODE_POINT});
            anyString = star(anyChar);
        }

        private Node intern(Key key, Node first, Node second, List<Node> members, int[] ranges, boolean nullable) {
            Node existing = interned.get(key);
            if (existing != null) {
                return existing;
            }
            if (interned.size() >= MAX_STATES) {
                throw SearchException.badRequest("The regular expression is too complex to evaluate.");
            }
            Node node = new Node(interned.size(), key.kind(), first, second, members, ranges, nullable);
            interned.put(key, node);
            return node;
        }

        Node nothing() {
            return nothing;
        }

        Node epsilon() {
            return epsilon;
        }

        Node anyChar() {
            return anyChar;
        }

        Node anyString() {
            return anyString;
        }

        Node literal(int codePoint) {
            return chars(new int[] {codePoint, codePoint});
        }

        Node string(String text) {
            List<Node> parts = new ArrayList<>();
            text.codePoints().forEach(codePoint -> parts.add(literal(codePoint)));
            return sequence(parts);
        }

        /** A character set from {@code [low, high]} pairs, in any order and possibly overlapping. */
        Node chars(int[] pairs) {
            int[] normalized = normalizeRanges(pairs);
            if (normalized.length == 0) {
                return nothing;
            }
            List<Integer> key = new ArrayList<>(normalized.length);
            for (int bound : normalized) {
                key.add(bound);
            }
            return intern(new Key(Kind.CHARS, -1, -1, List.of(), key), null, null, List.of(), normalized, false);
        }

        Node negatedChars(int[] pairs) {
            int[] normalized = normalizeRanges(pairs);
            List<Integer> complement = new ArrayList<>();
            int next = 0;
            for (int index = 0; index < normalized.length; index += 2) {
                if (normalized[index] > next) {
                    complement.add(next);
                    complement.add(normalized[index] - 1);
                }
                next = normalized[index + 1] + 1;
            }
            if (next <= Character.MAX_CODE_POINT) {
                complement.add(next);
                complement.add(Character.MAX_CODE_POINT);
            }
            return chars(complement.stream().mapToInt(Integer::intValue).toArray());
        }

        Node sequence(List<Node> parts) {
            Node result = epsilon;
            for (int index = parts.size() - 1; index >= 0; index--) {
                result = concat(parts.get(index), result);
            }
            return result;
        }

        Node concat(Node left, Node right) {
            if (left == nothing || right == nothing) {
                return nothing;
            }
            if (left == epsilon) {
                return right;
            }
            if (right == epsilon) {
                return left;
            }
            if (left.kind == Kind.CONCAT) {
                return concat(left.first, concat(left.second, right));
            }
            return intern(new Key(Kind.CONCAT, left.id, right.id, List.of(), List.of()), left, right, List.of(),
                null, left.nullable && right.nullable);
        }

        Node union(List<Node> alternatives) {
            Map<Integer, Node> members = new LinkedHashMap<>();
            List<int[]> charSets = new ArrayList<>();
            for (Node alternative : alternatives) {
                for (Node member : alternative.kind == Kind.UNION ? alternative.members : List.of(alternative)) {
                    if (member == anyString) {
                        return anyString;
                    }
                    if (member.kind == Kind.CHARS) {
                        charSets.add(member.ranges);
                    } else if (member != nothing) {
                        members.put(member.id, member);
                    }
                }
            }
            if (!charSets.isEmpty()) {
                Node merged = chars(concatRanges(charSets));
                members.put(merged.id, merged);
            }
            return combine(Kind.UNION, members, nothing);
        }

        Node intersection(List<Node> parts) {
            Map<Integer, Node> members = new LinkedHashMap<>();
            int[] charSet = null;
            for (Node part : parts) {
                for (Node member : part.kind == Kind.INTERSECTION ? part.members : List.of(part)) {
                    if (member == nothing) {
                        return nothing;
                    }
                    if (member.kind == Kind.CHARS) {
                        charSet = charSet == null ? member.ranges : intersectRanges(charSet, member.ranges);
                        if (charSet.length == 0) {
                            return nothing;
                        }
                    } else if (member != anyString) {
                        members.put(member.id, member);
                    }
                }
            }
            if (charSet != null) {
                Node merged = chars(charSet);
                members.put(merged.id, merged);
            }
            if (members.containsKey(epsilon.id)) {
                boolean allNullable = members.values().stream().allMatch(member -> member.nullable);
                return allNullable ? epsilon : nothing;
            }
            return combine(Kind.INTERSECTION, members, anyString);
        }

        private Node combine(Kind kind, Map<Integer, Node> members, Node whenEmpty) {
            if (members.isEmpty()) {
                return whenEmpty;
            }
            if (members.size() == 1) {
                return members.values().iterator().next();
            }
            List<Node> sorted = new ArrayList<>(members.values());
            sorted.sort((left, right) -> Integer.compare(left.id, right.id));
            List<Integer> ids = sorted.stream().map(member -> member.id).toList();
            boolean nullable = kind == Kind.UNION
                ? sorted.stream().anyMatch(member -> member.nullable)
                : sorted.stream().allMatch(member -> member.nullable);
            return intern(new Key(kind, -1, -1, ids, List.of()), null, null, List.copyOf(sorted), null, nullable);
        }

        Node complement(Node inner) {
            if (inner.kind == Kind.COMPLEMENT) {
                return inner.first;
            }
            if (inner == nothing) {
                return anyString;
            }
            if (inner == anyString) {
                return nothing;
            }
            return intern(new Key(Kind.COMPLEMENT, inner.id, -1, List.of(), List.of()), inner, null, List.of(), null,
                !inner.nullable);
        }

        Node star(Node inner) {
            if (inner.kind == Kind.STAR) {
                return inner;
            }
            if (inner == epsilon || inner == nothing) {
                return epsilon;
            }
            return intern(new Key(Kind.STAR, inner.id, -1, List.of(), List.of()), inner, null, List.of(), null, true);
        }

        Node optional(Node inner) {
            return union(List.of(inner, epsilon));
        }

        Node repeat(Node inner, int min, Integer max) {
            List<Node> parts = new ArrayList<>();
            for (int count = 0; count < min; count++) {
                parts.add(inner);
            }
            if (max == null) {
                parts.add(star(inner));
                return sequence(parts);
            }
            Node optionalTail = epsilon;
            for (int count = min; count < max; count++) {
                optionalTail = optional(concat(inner, optionalTail));
            }
            parts.add(optionalTail);
            return sequence(parts);
        }

        Node derivative(Node node, int codePoint) {
            long key = ((long) node.id << 21) | codePoint;
            Node cached = derivatives.get(key);
            if (cached != null) {
                return cached;
            }
            Node result = switch (node.kind) {
                case NOTHING, EPSILON -> nothing;
                case CHARS -> node.contains(codePoint) ? epsilon : nothing;
                case CONCAT -> {
                    Node head = concat(derivative(node.first, codePoint), node.second);
                    yield node.first.nullable ? union(List.of(head, derivative(node.second, codePoint))) : head;
                }
                case UNION -> union(node.members.stream().map(member -> derivative(member, codePoint)).toList());
                case INTERSECTION -> intersection(node.members.stream()
                    .map(member -> derivative(member, codePoint)).toList());
                case COMPLEMENT -> complement(derivative(node.first, codePoint));
                case STAR -> concat(derivative(node.first, codePoint), node);
            };
            derivatives.put(key, result);
            return result;
        }

        private static int[] normalizeRanges(int[] pairs) {
            List<int[]> ranges = new ArrayList<>();
            for (int index = 0; index < pairs.length; index += 2) {
                ranges.add(new int[] {pairs[index], pairs[index + 1]});
            }
            ranges.sort((left, right) -> Integer.compare(left[0], right[0]));
            List<Integer> merged = new ArrayList<>();
            for (int[] range : ranges) {
                int last = merged.size() - 1;
                if (last > 0 && range[0] <= merged.get(last) + 1) {
                    merged.set(last, Math.max(merged.get(last), range[1]));
                } else {
                    merged.add(range[0]);
                    merged.add(range[1]);
                }
            }
            return merged.stream().mapToInt(Integer::intValue).toArray();
        }

        private static int[] concatRanges(List<int[]> sets) {
            int length = sets.stream().mapToInt(set -> set.length).sum();
            int[] all = new int[length];
            int offset = 0;
            for (int[] set : sets) {
                System.arraycopy(set, 0, all, offset, set.length);
                offset += set.length;
            }
            return all;
        }

        private static int[] intersectRanges(int[] left, int[] right) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < left.length; i += 2) {
                for (int j = 0; j < right.length; j += 2) {
                    int low = Math.max(left[i], right[j]);
                    int high = Math.min(left[i + 1], right[j + 1]);
                    if (low <= high) {
                        result.add(low);
                        result.add(high);
                    }
                }
            }
            return normalizeRanges(result.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /** A recursive-descent parser of the documented grammar. */
    private static final class Parser {
        private final String pattern;
        private final Nodes nodes;
        private int position;

        Parser(String pattern, Nodes nodes) {
            this.pattern = pattern;
            this.nodes = nodes;
        }

        Node parse() {
            Node node = union();
            if (more()) {
                throw error("unexpected '" + new String(Character.toChars(peek())) + "'");
            }
            return node;
        }

        private Node union() {
            List<Node> alternatives = new ArrayList<>();
            alternatives.add(intersection());
            while (match('|')) {
                alternatives.add(intersection());
            }
            return alternatives.size() == 1 ? alternatives.get(0) : nodes.union(alternatives);
        }

        private Node intersection() {
            List<Node> parts = new ArrayList<>();
            parts.add(concatenation());
            while (match('&')) {
                parts.add(concatenation());
            }
            return parts.size() == 1 ? parts.get(0) : nodes.intersection(parts);
        }

        private Node concatenation() {
            List<Node> parts = new ArrayList<>();
            parts.add(repetition());
            while (more() && peek() != ')' && peek() != '|' && peek() != '&') {
                parts.add(repetition());
            }
            return nodes.sequence(parts);
        }

        private Node repetition() {
            Node node = complement();
            while (more()) {
                if (match('?')) {
                    node = nodes.optional(node);
                } else if (match('*')) {
                    node = nodes.star(node);
                } else if (match('+')) {
                    node = nodes.concat(node, nodes.star(node));
                } else if (peek() == '{') {
                    node = bounded(node);
                } else {
                    break;
                }
            }
            return node;
        }

        private Node bounded(Node node) {
            int start = position;
            position++;
            int min = number();
            Integer max = min;
            if (match(',')) {
                max = more() && Character.isDigit(peek()) ? number() : null;
            }
            if (!match('}')) {
                throw error("expected '}'");
            }
            if (max != null && max < min) {
                position = start;
                throw error("the repetition maximum is below its minimum");
            }
            if (min > MAX_REPEAT || (max != null && max > MAX_REPEAT)) {
                position = start;
                throw error("repetition counts above " + MAX_REPEAT + " are not supported");
            }
            return nodes.repeat(node, min, max);
        }

        private int number() {
            int start = position;
            while (more() && Character.isDigit(peek())) {
                position++;
            }
            if (start == position) {
                throw error("expected a number");
            }
            String digits = pattern.substring(start, position);
            return digits.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(digits);
        }

        private Node complement() {
            if (match('~')) {
                return nodes.complement(complement());
            }
            if (match('[')) {
                boolean negated = match('^');
                List<Integer> pairs = new ArrayList<>();
                do {
                    int low = character();
                    int high = low;
                    if (match('-')) {
                        high = character();
                        if (high < low) {
                            throw error("the character range is out of order");
                        }
                    }
                    pairs.add(low);
                    pairs.add(high);
                } while (more() && peek() != ']');
                if (!match(']')) {
                    throw error("expected ']'");
                }
                int[] ranges = pairs.stream().mapToInt(Integer::intValue).toArray();
                return negated ? nodes.negatedChars(ranges) : nodes.chars(ranges);
            }
            return simple();
        }

        private Node simple() {
            if (match('.')) {
                return nodes.anyChar();
            }
            if (match('#')) {
                return nodes.nothing();
            }
            if (match('@')) {
                return nodes.anyString();
            }
            if (match('"')) {
                int end = pattern.indexOf('"', position);
                if (end < 0) {
                    throw error("expected '\"'");
                }
                String text = pattern.substring(position, end);
                position = end + 1;
                return nodes.string(text);
            }
            if (match('(')) {
                if (match(')')) {
                    return nodes.epsilon();
                }
                Node inner = union();
                if (!match(')')) {
                    throw error("expected ')'");
                }
                return inner;
            }
            if (match('<')) {
                return interval();
            }
            return nodes.literal(character());
        }

        private Node interval() {
            int start = position;
            int end = pattern.indexOf('>', position);
            if (end < 0) {
                throw error("expected '>'");
            }
            String body = pattern.substring(position, end);
            int dash = body.indexOf('-');
            if (dash <= 0 || dash == body.length() - 1 || !digits(body.substring(0, dash))
                    || !digits(body.substring(dash + 1))) {
                position = start;
                throw error("named automata are not supported; only numeric intervals such as <1-10> are");
            }
            position = end + 1;
            return NumericInterval.of(nodes, body.substring(0, dash), body.substring(dash + 1));
        }

        private static boolean digits(String text) {
            return !text.isEmpty() && text.chars().allMatch(character -> character >= '0' && character <= '9');
        }

        private int character() {
            if (!more()) {
                throw error("unexpected end of the expression");
            }
            int codePoint = pattern.codePointAt(position);
            if (codePoint == '\\') {
                position++;
                if (!more()) {
                    throw error("the expression ends with an escape character");
                }
                int escaped = pattern.codePointAt(position);
                position += Character.charCount(escaped);
                return escaped;
            }
            if (RESERVED.indexOf(codePoint) >= 0 || codePoint == ')') {
                throw error("unexpected '" + new String(Character.toChars(codePoint)) + "'; escape reserved "
                    + "characters with '\\'");
            }
            position += Character.charCount(codePoint);
            return codePoint;
        }

        private boolean more() {
            return position < pattern.length();
        }

        private int peek() {
            return pattern.codePointAt(position);
        }

        private boolean match(char expected) {
            if (more() && pattern.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private SearchException error(String reason) {
            return SearchException.badRequest("Invalid regular expression '/" + pattern + "/': " + reason
                + " at position " + (position + 1) + ".");
        }
    }

    /**
     * The strings of decimal numbers in an interval, built digit by digit: a union of fixed-width digit ranges,
     * exactly as wide as the bounds when they have the same number of digits, else canonical numbers after any number
     * of leading zeros.
     */
    private static final class NumericInterval {

        private NumericInterval() {
        }

        static Node of(Nodes nodes, String lowText, String highText) {
            BigInteger low = new BigInteger(lowText);
            BigInteger high = new BigInteger(highText);
            if (low.compareTo(high) > 0) {
                BigInteger swap = low;
                low = high;
                high = swap;
            }
            if (lowText.length() == highText.length()) {
                int width = lowText.length();
                return fixed(nodes, pad(low, width), pad(high, width));
            }
            List<Node> widths = new ArrayList<>();
            int lowDigits = low.toString().length();
            int highDigits = high.toString().length();
            for (int width = lowDigits; width <= highDigits; width++) {
                BigInteger smallest = width == 1 ? BigInteger.ZERO : BigInteger.TEN.pow(width - 1);
                BigInteger largest = BigInteger.TEN.pow(width).subtract(BigInteger.ONE);
                BigInteger from = low.max(smallest);
                BigInteger to = high.min(largest);
                if (from.compareTo(to) <= 0) {
                    widths.add(fixed(nodes, pad(from, width), pad(to, width)));
                }
            }
            return nodes.concat(nodes.star(nodes.literal('0')), nodes.union(widths));
        }

        private static String pad(BigInteger value, int width) {
            String digits = value.toString();
            return "0".repeat(Math.max(0, width - digits.length())) + digits;
        }

        /** Digit strings of the bounds' width between {@code low} and {@code high}, both padded to that width. */
        private static Node fixed(Nodes nodes, String low, String high) {
            if (low.isEmpty()) {
                return nodes.epsilon();
            }
            char lowFirst = low.charAt(0);
            char highFirst = high.charAt(0);
            String lowRest = low.substring(1);
            String highRest = high.substring(1);
            if (lowFirst == highFirst) {
                return nodes.concat(nodes.literal(lowFirst), fixed(nodes, lowRest, highRest));
            }
            List<Node> parts = new ArrayList<>();
            parts.add(nodes.concat(nodes.literal(lowFirst), fixed(nodes, lowRest, "9".repeat(lowRest.length()))));
            if (lowFirst + 1 <= highFirst - 1) {
                parts.add(nodes.concat(nodes.chars(new int[] {lowFirst + 1, highFirst - 1}),
                    anyDigits(nodes, lowRest.length())));
            }
            parts.add(nodes.concat(nodes.literal(highFirst), fixed(nodes, "0".repeat(highRest.length()), highRest)));
            return nodes.union(parts);
        }

        private static Node anyDigits(Nodes nodes, int count) {
            Node digit = nodes.chars(new int[] {'0', '9'});
            List<Node> parts = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                parts.add(digit);
            }
            return nodes.sequence(parts);
        }
    }
}
