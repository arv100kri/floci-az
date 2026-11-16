package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates the analysis sections of an index definition ({@code analyzers}, {@code tokenizers}, {@code tokenFilters},
 * {@code charFilters}, {@code normalizers}) per Create Index (REST) and "Add custom analyzers": component names follow
 * the documented naming rule (letters, digits, spaces, dashes and underscores, starting and ending with a letter or
 * digit, at most 128 characters) and do not reuse a built-in name; each {@code @odata.type} is one the section
 * supports; parameters have their documented types and limits; and every tokenizer, token filter and character filter
 * an analyzer or normalizer names exists, normalizers naming only the filters they support. The parser has already
 * checked that each entry has a name and a type and that names are unique within their section.
 */
public final class AnalysisDefinitionValidator {

    private static final Pattern COMPONENT_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9 _-]{0,126}[A-Za-z0-9])?");
    private static final List<String> SECTIONS = List.of(
        "charFilters", "tokenizers", "tokenFilters", "analyzers", "normalizers");

    private AnalysisDefinitionValidator() {
    }

    /**
     * Validates {@code index}, a normalised index definition.
     *
     * @throws SearchException a 400 naming the first problem
     */
    public static void validate(ObjectNode index, AnalyzerRegistry registry) {
        AnalysisComponents components = AnalysisComponents.of(index);
        for (String section : SECTIONS) {
            for (JsonNode entry : index.path(section)) {
                String name = SearchJson.text(entry, "name");
                checkName(section, name, registry);
                try {
                    switch (section) {
                        case "analyzers" -> components.analyzer(entry);
                        case "normalizers" -> components.normalizer(entry);
                        case "tokenizers" -> components.tokenizer(name);
                        case "tokenFilters" -> components.tokenFilter(name);
                        default -> components.charFilter(name);
                    }
                } catch (IllegalArgumentException e) {
                    throw invalid(e.getMessage());
                }
            }
        }
    }

    private static void checkName(String section, String name, AnalyzerRegistry registry) {
        if (!COMPONENT_NAME.matcher(name).matches()) {
            throw invalid("the name '" + name + "' in '" + section + "' is invalid. Names may contain only letters, "
                + "digits, spaces, dashes or underscores, must start and end with a letter or digit, and are limited "
                + "to 128 characters.");
        }
        boolean builtIn = switch (section) {
            case "analyzers" -> registry.analyzerNames().contains(name)
                || BuiltInComponents.TOKENIZER_NAMES.contains(name)
                || BuiltInComponents.TOKEN_FILTER_NAMES.contains(name)
                || BuiltInComponents.CHAR_FILTER_NAMES.contains(name);
            case "tokenizers" -> BuiltInComponents.TOKENIZER_NAMES.contains(name);
            case "tokenFilters" -> BuiltInComponents.TOKEN_FILTER_NAMES.contains(name);
            case "charFilters" -> BuiltInComponents.CHAR_FILTER_NAMES.contains(name);
            default -> registry.normalizerNames().contains(name);
        };
        if (builtIn) {
            throw invalid("the name '" + name + "' in '" + section + "' is the name of a built-in analysis "
                + "component; custom components must have names of their own.");
        }
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: definition : " + detail);
    }
}
