package io.floci.az.services.search.engine.odata;

import io.floci.az.services.search.model.SearchException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an OData expression of Azure AI Search's dialect ({@code $filter}, {@code $orderby}) into tokens, per the
 * "OData expression syntax reference" grammar. Identifiers may contain dots ({@code search.in}, {@code geo.distance});
 * date-time literals, numbers ({@code NaN}, {@code INF} and {@code -INF} included), quoted strings and
 * {@code geography'...'} literals are single tokens.
 */
final class ODataLexer {

    enum Kind {
        IDENTIFIER, STRING, INTEGER, FLOAT, DATE_TIME, GEOGRAPHY, SLASH, COMMA, COLON, OPEN, CLOSE, END
    }

    record Token(Kind kind, String text, int position) {

        boolean isKeyword(String keyword) {
            return kind == Kind.IDENTIFIER && text.equalsIgnoreCase(keyword);
        }
    }

    private static final Pattern DATE_TIME = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    private ODataLexer() {
    }

    static List<Token> tokenize(String text, String parameter) {
        List<Token> tokens = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            char character = text.charAt(position);
            if (Character.isWhitespace(character)) {
                position++;
                continue;
            }
            int start = position;
            switch (character) {
                case '/' -> {
                    tokens.add(new Token(Kind.SLASH, "/", start));
                    position++;
                }
                case ',' -> {
                    tokens.add(new Token(Kind.COMMA, ",", start));
                    position++;
                }
                case ':' -> {
                    tokens.add(new Token(Kind.COLON, ":", start));
                    position++;
                }
                case '(' -> {
                    tokens.add(new Token(Kind.OPEN, "(", start));
                    position++;
                }
                case ')' -> {
                    tokens.add(new Token(Kind.CLOSE, ")", start));
                    position++;
                }
                case '\'' -> {
                    StringBuilder value = new StringBuilder();
                    position = readQuoted(text, position, value, parameter);
                    tokens.add(new Token(Kind.STRING, value.toString(), start));
                }
                default -> position = readValue(text, position, tokens, parameter);
            }
        }
        tokens.add(new Token(Kind.END, "", text.length()));
        return tokens;
    }

    private static int readValue(String text, int start, List<Token> tokens, String parameter) {
        char character = text.charAt(start);
        if (Character.isDigit(character) || ((character == '-' || character == '+') && start + 1 < text.length()
                && Character.isDigit(text.charAt(start + 1)))) {
            Matcher dateTime = DATE_TIME.matcher(text).region(start, text.length());
            if (dateTime.lookingAt()) {
                tokens.add(new Token(Kind.DATE_TIME, dateTime.group(), start));
                return dateTime.end();
            }
            Matcher number = NUMBER.matcher(text).region(start, text.length());
            if (number.lookingAt()) {
                boolean floating = number.group(1) != null || number.group(2) != null;
                tokens.add(new Token(floating ? Kind.FLOAT : Kind.INTEGER, number.group(), start));
                return number.end();
            }
        }
        if (text.startsWith("-INF", start)) {
            tokens.add(new Token(Kind.FLOAT, "-INF", start));
            return start + 4;
        }
        if (Character.isLetter(character) || character == '_') {
            int end = start + 1;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_'
                    || text.charAt(end) == '.')) {
                end++;
            }
            String identifier = text.substring(start, end);
            if (identifier.equalsIgnoreCase("geography") && end < text.length() && text.charAt(end) == '\'') {
                StringBuilder value = new StringBuilder();
                int next = readQuoted(text, end, value, parameter);
                tokens.add(new Token(Kind.GEOGRAPHY, value.toString(), start));
                return next;
            }
            if (identifier.equals("NaN") || identifier.equals("INF")) {
                tokens.add(new Token(Kind.FLOAT, identifier, start));
            } else {
                tokens.add(new Token(Kind.IDENTIFIER, identifier, start));
            }
            return end;
        }
        throw syntax(parameter, "unexpected character '" + character + "' at position " + start);
    }

    /** Reads a {@code '...'} literal starting at {@code start}; {@code ''} is an escaped quote. */
    private static int readQuoted(String text, int start, StringBuilder value, String parameter) {
        int position = start + 1;
        while (position < text.length()) {
            char character = text.charAt(position);
            if (character == '\'') {
                if (position + 1 < text.length() && text.charAt(position + 1) == '\'') {
                    value.append('\'');
                    position += 2;
                    continue;
                }
                return position + 1;
            }
            value.append(character);
            position++;
        }
        throw syntax(parameter, "unterminated string literal starting at position " + start);
    }

    static SearchException syntax(String parameter, String detail) {
        return SearchException.badRequest("Invalid expression: Syntax error: " + detail + ".\r\nParameter name: "
            + parameter);
    }
}
