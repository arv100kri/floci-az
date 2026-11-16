package io.floci.az.services.search.indexer;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits delimited text into records the way Learn's "Index CSV blobs" describes: a field holding the delimiter is
 * wrapped in quotes, and a quote inside a quoted field is doubled. Whitespace around a field (and before its opening
 * quote) is dropped, quoted line breaks stay in the field, and blank lines are skipped.
 */
final class DelimitedText {

    private DelimitedText() {
    }

    /** The records of {@code text}, each a list of field values. */
    static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int index = 0;
        int length = text.length();
        boolean fieldStarted = false;
        while (index < length) {
            char c = text.charAt(index);
            if (!fieldStarted && (c == ' ' || c == '\t') && c != delimiter) {
                index++;
                continue;
            }
            if (!fieldStarted && c == '"') {
                index = readQuoted(text, index + 1, field);
                while (index < length && (text.charAt(index) == ' ' || text.charAt(index) == '\t')
                        && text.charAt(index) != delimiter) {
                    index++;
                }
                fieldStarted = true;
                continue;
            }
            if (c == delimiter) {
                fields.add(fieldStarted ? field.toString().strip() : "");
                field.setLength(0);
                fieldStarted = false;
                index++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                if (fieldStarted || !fields.isEmpty()) {
                    fields.add(field.toString().strip());
                    records.add(fields);
                }
                fields = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
                index += c == '\r' && index + 1 < length && text.charAt(index + 1) == '\n' ? 2 : 1;
                continue;
            }
            field.append(c);
            fieldStarted = true;
            index++;
        }
        if (fieldStarted || !fields.isEmpty()) {
            fields.add(field.toString().strip());
            records.add(fields);
        }
        return records;
    }

    /** Reads a quoted field starting after its opening quote; returns the index after the closing quote. */
    private static int readQuoted(String text, int start, StringBuilder field) {
        int index = start;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '"') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            field.append(c);
            index++;
        }
        return index;
    }

    /** The header names a {@code delimitedTextHeaders} setting lists, which is always comma-delimited. */
    static List<String> headers(String configured) {
        List<List<String>> parsed = parse(configured, ',');
        return parsed.isEmpty() ? List.of() : parsed.getFirst();
    }
}
