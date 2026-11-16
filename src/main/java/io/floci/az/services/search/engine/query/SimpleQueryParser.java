package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.query.QueryNode.Bool;
import io.floci.az.services.search.engine.query.QueryNode.Clause;
import io.floci.az.services.search.engine.query.QueryNode.MatchAll;
import io.floci.az.services.search.engine.query.QueryNode.Occur;
import io.floci.az.services.search.engine.query.QueryNode.Phrase;
import io.floci.az.services.search.engine.query.QueryNode.Prefix;
import io.floci.az.services.search.engine.query.QueryNode.Term;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The simple query syntax, the default {@code queryType}, per Learn's "Simple query syntax" page:
 * <ul>
 *   <li>{@code +} is AND and {@code |} is OR between the tokens around it; with no operator, tokens combine with the
 *       search mode's default ({@code any}: OR, {@code all}: AND);</li>
 *   <li>{@code -} at the start of a token negates it: with {@code searchMode=any} {@code pool -ocean} means
 *       "pool OR NOT ocean", with {@code all} "pool AND NOT ocean";</li>
 *   <li>{@code "..."} is a phrase, {@code *} at the end of a token a prefix query, a lone {@code *} every
 *       document;</li>
 *   <li>parentheses group, and operators apply left to right: {@code a | b + c} is {@code (a OR b) AND c};</li>
 *   <li>{@code \} escapes the next character; {@code -} needs escaping only at the start of a token and {@code *}
 *       only at its end.</li>
 * </ul>
 * Malformed input is interpreted rather than refused: an unclosed quote runs to the end, unbalanced parentheses are
 * closed or ignored. Fuzzy and proximity operators are not part of Azure's simple syntax, so {@code ~} is an ordinary
 * character.
 */
public final class SimpleQueryParser implements QueryParser {

    private static final int MAX_QUERY_LENGTH = 100_000;
    private static final int MAX_CLAUSES = 1024;
    private static final int MAX_PREFIX_LENGTH = 1000;

    @Override
    public QueryNode parse(String text, SearchMode mode) {
        if (text.length() > MAX_QUERY_LENGTH) {
            throw SearchException.badRequest("The search query is " + text.length() + " characters long; the maximum "
                + "is " + MAX_QUERY_LENGTH + ".");
        }
        QueryNode node = new Cursor(text, mode).sequence(false);
        return node == null ? new MatchAll() : node;
    }

    private static final class Cursor {
        private final String text;
        private final Occur defaultOccur;
        private int position;
        private int clauses;

        Cursor(String text, SearchMode mode) {
            this.text = text;
            this.defaultOccur = mode == SearchMode.ALL ? Occur.MUST : Occur.SHOULD;
        }

        QueryNode sequence(boolean nested) {
            List<QueryNode> items = new ArrayList<>();
            Occur groupOccur = defaultOccur;
            Occur pending = null;
            int negations = 0;
            while (position < text.length()) {
                char character = text.charAt(position);
                if (Character.isWhitespace(character)) {
                    position++;
                    continue;
                }
                if (character == ')') {
                    if (nested) {
                        break;
                    }
                    position++;
                    continue;
                }
                if (character == '+' || character == '|') {
                    pending = character == '+' ? Occur.MUST : Occur.SHOULD;
                    position++;
                    continue;
                }
                if (character == '-') {
                    negations++;
                    position++;
                    continue;
                }
                QueryNode node;
                if (character == '(') {
                    position++;
                    node = sequence(true);
                    if (position < text.length() && text.charAt(position) == ')') {
                        position++;
                    }
                } else if (character == '"') {
                    node = phrase();
                } else {
                    node = term();
                }
                if (node == null) {
                    negations = 0;
                    continue;
                }
                if (negations % 2 == 1) {
                    node = new Bool(List.of(new Clause(Occur.SHOULD, new MatchAll()),
                        new Clause(Occur.MUST_NOT, node)));
                }
                negations = 0;
                Occur occur = pending != null ? pending : defaultOccur;
                pending = null;
                if (items.size() <= 1) {
                    if (items.size() == 1) {
                        groupOccur = occur;
                    }
                    items.add(node);
                } else if (occur == groupOccur) {
                    items.add(node);
                } else {
                    QueryNode collapsed = group(items, groupOccur);
                    items = new ArrayList<>(List.of(collapsed, node));
                    groupOccur = occur;
                }
            }
            if (items.isEmpty()) {
                return null;
            }
            return items.size() == 1 ? items.get(0) : group(items, groupOccur);
        }

        private QueryNode term() {
            StringBuilder word = new StringBuilder();
            boolean lastEscaped = false;
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character == '\\') {
                    if (position + 1 < text.length()) {
                        word.append(text.charAt(position + 1));
                        position += 2;
                        lastEscaped = true;
                        continue;
                    }
                    position++;
                    break;
                }
                if (Character.isWhitespace(character) || character == '+' || character == '|' || character == '"'
                        || character == '(' || character == ')') {
                    break;
                }
                word.append(character);
                lastEscaped = false;
                position++;
            }
            if (word.isEmpty()) {
                return null;
            }
            countClause();
            String value = word.toString();
            if (!lastEscaped && value.endsWith("*")) {
                String prefix = value.substring(0, value.length() - 1);
                if (prefix.isEmpty()) {
                    return new MatchAll();
                }
                if (prefix.length() > MAX_PREFIX_LENGTH) {
                    throw SearchException.badRequest("The prefix term '" + prefix.substring(0, 50) + "...' is longer "
                        + "than the maximum of " + MAX_PREFIX_LENGTH + " characters for prefix search.");
                }
                return new Prefix(prefix);
            }
            return new Term(value, defaultOccur);
        }

        private QueryNode phrase() {
            position++;
            StringBuilder phrase = new StringBuilder();
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character == '\\' && position + 1 < text.length()) {
                    phrase.append(text.charAt(position + 1));
                    position += 2;
                    continue;
                }
                position++;
                if (character == '"') {
                    break;
                }
                phrase.append(character);
            }
            if (phrase.toString().isBlank()) {
                return null;
            }
            countClause();
            return new Phrase(phrase.toString(), 0);
        }

        private void countClause() {
            if (++clauses > MAX_CLAUSES) {
                throw SearchException.badRequest("The search query has more than the maximum of " + MAX_CLAUSES
                    + " clauses.");
            }
        }

        private static QueryNode group(List<QueryNode> items, Occur occur) {
            List<Clause> clauses = new ArrayList<>(items.size());
            for (QueryNode item : items) {
                clauses.add(new Clause(occur, item));
            }
            return new Bool(clauses);
        }
    }
}
