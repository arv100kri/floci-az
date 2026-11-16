package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.model.SearchException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the request's {@code scoringParameters}, per the Search Documents reference: each entry is
 * {@code name-values}, the name up to the first dash and the values comma-separated after it, so
 * {@code mylocation--122.2,44.8} gives {@code mylocation} the values {@code -122.2} and {@code 44.8}. A value in single
 * quotes may hold commas, a doubled quote standing for one. A later entry with the same name replaces an earlier one.
 */
final class ScoringParameters {

    private ScoringParameters() {
    }

    static Map<String, List<String>> parse(List<String> entries) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (String entry : entries) {
            int dash = entry.indexOf('-');
            if (dash <= 0) {
                throw SearchException.badRequest("Invalid scoring parameter '" + entry + "'. Scoring parameters "
                    + "take the form name-value1,value2,...\r\nParameter name: scoringParameter");
            }
            parameters.put(entry.substring(0, dash).trim(), values(entry, entry.substring(dash + 1)));
        }
        return parameters;
    }

    private static List<String> values(String entry, String text) {
        List<String> values = new ArrayList<>();
        if (text.isEmpty()) {
            return values;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '\'' && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                    current.append('\'');
                    index += 2;
                    continue;
                }
                if (character == '\'') {
                    quoted = false;
                } else {
                    current.append(character);
                }
            } else if (character == '\'' && current.toString().isBlank()) {
                quoted = true;
                current.setLength(0);
            } else if (character == ',') {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
            index++;
        }
        if (quoted) {
            throw SearchException.badRequest("Invalid scoring parameter '" + entry + "': a quoted value is not closed."
                + "\r\nParameter name: scoringParameter");
        }
        values.add(current.toString().trim());
        return values;
    }
}
