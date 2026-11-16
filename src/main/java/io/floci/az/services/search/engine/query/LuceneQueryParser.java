package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.query.QueryNode.Bool;
import io.floci.az.services.search.engine.query.QueryNode.Boost;
import io.floci.az.services.search.engine.query.QueryNode.Clause;
import io.floci.az.services.search.engine.query.QueryNode.FieldScoped;
import io.floci.az.services.search.engine.query.QueryNode.Fuzzy;
import io.floci.az.services.search.engine.query.QueryNode.MatchAll;
import io.floci.az.services.search.engine.query.QueryNode.Occur;
import io.floci.az.services.search.engine.query.QueryNode.Phrase;
import io.floci.az.services.search.engine.query.QueryNode.Prefix;
import io.floci.az.services.search.engine.query.QueryNode.Regex;
import io.floci.az.services.search.engine.query.QueryNode.Term;
import io.floci.az.services.search.engine.query.QueryNode.Wildcard;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The full Lucene query syntax ({@code queryType=full}), per Learn's "Lucene query syntax in Azure AI Search":
 * <ul>
 *   <li>fielded search: {@code field:term}, {@code field:"phrase"}, {@code field:(group)}, including subfield paths
 *       ({@code Address/City:Seattle}); the field must be searchable, and the prefix applies only to what directly
 *       follows it;</li>
 *   <li>boolean operators {@code AND}/{@code &&}, {@code OR}/{@code ||}, {@code NOT}/{@code !}, {@code +} and
 *       {@code -} (the words in capitals only; a single {@code |} or {@code &} is an ordinary character). As in the
 *       Lucene query parser, operators set each clause's occurrence from left to right rather than by precedence:
 *       {@code AND} makes the clauses on both sides required, {@code OR} makes the next clause optional (and, under
 *       {@code searchMode=all}, the previous one too), and a negated clause only removes documents, so a query of
 *       negations alone matches nothing. Parentheses group;</li>
 *   <li>phrases with proximity ({@code "hotel airport"~5}), fuzzy terms ({@code blue~}, {@code blue~1}; at most two
 *       edits, two by default) and boosts ({@code tax^2}, {@code "deferred tax"^2}, {@code (a OR b)^2});</li>
 *   <li>regular expressions between slashes ({@code /[mh]otel/}, see {@link LuceneRegExp}) and wildcard terms with
 *       {@code *} and {@code ?}, which may not start with a wildcard; a term ending in its only {@code *} is a prefix
 *       query. Prefix, wildcard and regex terms are at most 1000 characters long;</li>
 *   <li>{@code \} escapes the special characters {@code + - & | ! ( ) { } [ ] ^ " ~ * ? : \ /}.</li>
 * </ul>
 * Range searches ({@code [a TO b]}) are not part of Azure's syntax (they belong in {@code $filter}), so brackets and
 * braces must be escaped. Malformed input is a 400 naming the line and column, as the service answers. Term and phrase
 * text is analyzed later, per field; prefix, wildcard, regex and fuzzy terms are only normalised (lowercased).
 */
public final class LuceneQueryParser implements QueryParser {

    private static final int MAX_QUERY_LENGTH = 100_000;
    private static final int MAX_CLAUSES = 3_000;
    private static final int MAX_PATTERN_LENGTH = 1_000;
    private static final int DEFAULT_FUZZY_EDITS = 2;
    private static final String TERM_ENDERS = "():^~\"![]{}";

    @Override
    public QueryNode parse(String text, SearchMode mode) {
        if (text.length() > MAX_QUERY_LENGTH) {
            throw SearchException.badRequest("The search query is " + text.length() + " characters long; the maximum "
                + "is " + MAX_QUERY_LENGTH + ".");
        }
        if (text.isBlank() || text.trim().equals("*")) {
            return new MatchAll();
        }
        return new Cursor(text, mode).query();
    }

    private enum Conjunction {
        NONE, AND, OR
    }

    private enum Modifier {
        NONE, REQUIRED, PROHIBITED
    }

    private static final class Cursor {
        private final String text;
        private final boolean defaultAnd;
        private final Occur defaultOccur;
        private int position;
        private int clauses;

        Cursor(String text, SearchMode mode) {
            this.text = text;
            this.defaultAnd = mode == SearchMode.ALL;
            this.defaultOccur = defaultAnd ? Occur.MUST : Occur.SHOULD;
        }

        QueryNode query() {
            QueryNode node = group();
            if (!atEnd()) {
                throw failure(position);
            }
            return node;
        }

        /** Clauses up to the end of the text or the closing parenthesis of a group. */
        private QueryNode group() {
            List<Clause> items = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (atEnd() || peek() == ')') {
                    break;
                }
                int clauseStart = position;
                Conjunction conjunction = conjunction();
                if (conjunction != Conjunction.NONE) {
                    if (items.isEmpty()) {
                        throw failure(clauseStart);
                    }
                    skipWhitespace();
                }
                Modifier modifier = modifier();
                skipWhitespace();
                if (atEnd() || peek() == ')' || conjunctionAhead()) {
                    throw failure(position);
                }
                add(items, conjunction, modifier, clause());
            }
            if (items.isEmpty()) {
                throw failure(position);
            }
            if (items.size() == 1 && items.get(0).occur() != Occur.MUST_NOT) {
                return items.get(0).query();
            }
            return new Bool(items);
        }

        /** Sets the occurrence of a new clause and of the one before it, the way the Lucene query parser does. */
        private void add(List<Clause> items, Conjunction conjunction, Modifier modifier, QueryNode node) {
            if (!items.isEmpty()) {
                int last = items.size() - 1;
                Clause previous = items.get(last);
                if (previous.occur() != Occur.MUST_NOT) {
                    if (conjunction == Conjunction.AND) {
                        items.set(last, new Clause(Occur.MUST, previous.query()));
                    } else if (conjunction == Conjunction.OR && defaultAnd) {
                        items.set(last, new Clause(Occur.SHOULD, previous.query()));
                    }
                }
            }
            Occur occur;
            if (modifier == Modifier.PROHIBITED) {
                occur = Occur.MUST_NOT;
            } else if (modifier == Modifier.REQUIRED || conjunction == Conjunction.AND) {
                occur = Occur.MUST;
            } else if (conjunction == Conjunction.OR) {
                occur = Occur.SHOULD;
            } else {
                occur = defaultOccur;
            }
            items.add(new Clause(occur, node));
        }

        private Conjunction conjunction() {
            if (startsWith("&&")) {
                position += 2;
                return Conjunction.AND;
            }
            if (startsWith("||")) {
                position += 2;
                return Conjunction.OR;
            }
            if (keyword("AND")) {
                position += 3;
                return Conjunction.AND;
            }
            if (keyword("OR")) {
                position += 2;
                return Conjunction.OR;
            }
            return Conjunction.NONE;
        }

        private boolean conjunctionAhead() {
            return startsWith("&&") || startsWith("||") || keyword("AND") || keyword("OR");
        }

        private Modifier modifier() {
            if (atEnd()) {
                return Modifier.NONE;
            }
            char character = peek();
            if (character == '+') {
                position++;
                return Modifier.REQUIRED;
            }
            if (character == '-' || character == '!') {
                position++;
                return Modifier.PROHIBITED;
            }
            if (keyword("NOT")) {
                position += 3;
                return Modifier.PROHIBITED;
            }
            return Modifier.NONE;
        }

        /** A clause: an optional field prefix, then a term, phrase, regular expression or group with its suffixes. */
        private QueryNode clause() {
            int start = position;
            if (peek() != '(' && peek() != '"' && peek() != '/') {
                RawTerm field = rawTerm();
                if (!field.raw().isEmpty() && !atEnd() && peek() == ':') {
                    position++;
                    skipWhitespace();
                    if (atEnd() || peek() == ')' || peek() == '+' || peek() == '-' || peek() == '!'
                            || conjunctionAhead()) {
                        throw failure(position);
                    }
                    if (field.raw().equals("*") && startsWith("*") && endsWord(position + 1)) {
                        position++;
                        return new MatchAll();
                    }
                    return new FieldScoped(field.text(), primary());
                }
                position = start;
            }
            return primary();
        }

        private QueryNode primary() {
            int start = position;
            char character = peek();
            if (character == '(') {
                position++;
                QueryNode node = group();
                if (atEnd() || peek() != ')') {
                    throw failure(position);
                }
                position++;
                return boosted(node);
            }
            if (character == '"') {
                return phrase();
            }
            if (character == '/') {
                return regex();
            }
            RawTerm term = rawTerm();
            if (term.raw().isEmpty()) {
                throw failure(start);
            }
            countClause();
            return termQuery(term, start);
        }

        private QueryNode phrase() {
            int start = position;
            position++;
            StringBuilder phrase = new StringBuilder();
            boolean closed = false;
            while (!atEnd()) {
                char character = text.charAt(position++);
                if (character == '\\' && !atEnd()) {
                    phrase.append(text.charAt(position++));
                } else if (character == '"') {
                    closed = true;
                    break;
                } else {
                    phrase.append(character);
                }
            }
            if (!closed) {
                throw failure(start);
            }
            countClause();
            int slop = 0;
            Float boost = null;
            for (int suffix = 0; suffix < 2 && !atEnd(); suffix++) {
                if (peek() == '~') {
                    int at = position++;
                    String number = number();
                    slop = number.isEmpty() ? 0 : (int) parseNumber(number, at);
                } else if (peek() == '^') {
                    boost = boostValue();
                }
            }
            QueryNode node = new Phrase(phrase.toString(), slop);
            return boost == null ? node : new Boost(node, boost);
        }

        private QueryNode regex() {
            int start = position;
            position++;
            StringBuilder pattern = new StringBuilder();
            boolean closed = false;
            while (!atEnd()) {
                char character = text.charAt(position++);
                if (character == '\\' && !atEnd()) {
                    char escaped = text.charAt(position++);
                    if (escaped != '/') {
                        pattern.append(character);
                    }
                    pattern.append(escaped);
                } else if (character == '/') {
                    closed = true;
                    break;
                } else {
                    pattern.append(character);
                }
            }
            if (!closed) {
                throw failure(start);
            }
            if (pattern.length() > MAX_PATTERN_LENGTH) {
                throw tooLong("regular expression", pattern.toString());
            }
            countClause();
            return boosted(new Regex(pattern.toString()));
        }

        /** A term, prefix, wildcard or fuzzy query from a bare word and its {@code ~} and {@code ^} suffixes. */
        private QueryNode termQuery(RawTerm term, int start) {
            Integer edits = null;
            Float boost = null;
            for (int suffix = 0; suffix < 2 && !atEnd(); suffix++) {
                if (peek() == '~') {
                    int at = position++;
                    String number = number();
                    edits = number.isEmpty() ? DEFAULT_FUZZY_EDITS : fuzzyEdits(number, at);
                } else if (peek() == '^') {
                    boost = boostValue();
                }
            }
            QueryNode node;
            if (term.wildcards() > 0) {
                if (term.raw().startsWith("*") || term.raw().startsWith("?")) {
                    throw failure(start);
                }
                if (term.text().length() > MAX_PATTERN_LENGTH) {
                    throw tooLong("wildcard term", term.text());
                }
                boolean prefix = term.wildcards() == 1 && term.raw().endsWith("*") && !term.raw().endsWith("\\*");
                node = prefix ? new Prefix(term.text().substring(0, term.text().length() - 1))
                    : new Wildcard(term.raw());
            } else if (edits != null) {
                node = new Fuzzy(term.text(), edits);
            } else {
                node = new Term(term.text(), defaultOccur);
            }
            return boost == null ? node : new Boost(node, boost);
        }

        private QueryNode boosted(QueryNode node) {
            if (!atEnd() && peek() == '^') {
                return new Boost(node, boostValue());
            }
            return node;
        }

        private float boostValue() {
            int at = position++;
            String number = number();
            if (number.isEmpty()) {
                throw failure(at);
            }
            float value = parseNumber(number, at);
            if (!(value > 0)) {
                throw failure(at);
            }
            return value;
        }

        private int fuzzyEdits(String number, int at) {
            float value = parseNumber(number, at);
            if (value != Math.floor(value) || value > DEFAULT_FUZZY_EDITS) {
                throw failure(at);
            }
            return (int) value;
        }

        private String number() {
            int start = position;
            while (!atEnd() && (Character.isDigit(peek()) || peek() == '.')) {
                position++;
            }
            return text.substring(start, position);
        }

        private float parseNumber(String number, int at) {
            try {
                return Float.parseFloat(number);
            } catch (NumberFormatException e) {
                throw failure(at);
            }
        }

        /**
         * A bare word up to a space or a special character. {@code +} and {@code -} may appear inside a word, and so
         * may {@code /}, for subfield paths such as {@code Address/City}; a backslash escapes the next character.
         */
        private RawTerm rawTerm() {
            StringBuilder raw = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            int wildcards = 0;
            while (!atEnd()) {
                char character = peek();
                if (character == '\\') {
                    if (position + 1 >= text.length()) {
                        throw failure(position);
                    }
                    char escaped = text.charAt(position + 1);
                    raw.append(character).append(escaped);
                    plain.append(escaped);
                    position += 2;
                    continue;
                }
                boolean first = raw.isEmpty();
                if (Character.isWhitespace(character) || TERM_ENDERS.indexOf(character) >= 0
                        || (first && (character == '/' || character == '+' || character == '-'))
                        || (first && (startsWith("&&") || startsWith("||")))) {
                    break;
                }
                if (character == '*' || character == '?') {
                    wildcards++;
                }
                raw.append(character);
                plain.append(character);
                position++;
            }
            return new RawTerm(raw.toString(), plain.toString(), wildcards);
        }

        private boolean keyword(String word) {
            return startsWith(word) && endsWord(position + word.length());
        }

        private boolean endsWord(int index) {
            if (index >= text.length()) {
                return true;
            }
            char next = text.charAt(index);
            return Character.isWhitespace(next) || next == '(' || next == ')' || next == '"';
        }

        private boolean startsWith(String prefix) {
            return text.startsWith(prefix, position);
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(peek())) {
                position++;
            }
        }

        private boolean atEnd() {
            return position >= text.length();
        }

        private char peek() {
            return text.charAt(position);
        }

        private void countClause() {
            if (++clauses > MAX_CLAUSES) {
                throw SearchException.badRequest("The search query has more than the maximum of " + MAX_CLAUSES
                    + " clauses.");
            }
        }

        private static SearchException tooLong(String what, String value) {
            return SearchException.badRequest("The " + what + " '" + value.substring(0, 50) + "...' is longer than "
                + "the maximum of " + MAX_PATTERN_LENGTH + " characters.");
        }

        /** The service's answer to a query it cannot parse, with the 1-based line and column of {@code index}. */
        private SearchException failure(int index) {
            int line = 1;
            int lineStart = 0;
            for (int at = 0; at < Math.min(index, text.length()); at++) {
                if (text.charAt(at) == '\n') {
                    line++;
                    lineStart = at + 1;
                }
            }
            return SearchException.badRequest("Failed to parse query string at line " + line + ", column "
                + (index - lineStart + 1) + ". See https://aka.ms/azure-search-full-query for supported syntax.");
        }
    }

    /**
     * A bare word as written.
     *
     * @param raw       the word with its escapes, as wildcard patterns keep it
     * @param text      the word without escapes
     * @param wildcards the number of unescaped {@code *} and {@code ?}
     */
    private record RawTerm(String raw, String text, int wildcards) {
    }
}
