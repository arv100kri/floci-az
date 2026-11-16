package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchException;

/**
 * The {@code hybridSearch} options of a request, per "Create a hybrid query".
 *
 * @param maxTextRecallSize the most BM25-ranked documents the text query contributes to fusion: 1 to 10,000, 1,000 by
 *                          default
 * @param countAllResults   whether {@code @odata.count} and facets count every text match ({@code countAllResults},
 *                          the default) or only the documents retrieved for fusion ({@code countRetrievableResults})
 */
public record HybridSearchOptions(int maxTextRecallSize, boolean countAllResults) {

    public static final int DEFAULT_MAX_TEXT_RECALL_SIZE = 1000;
    public static final int MAX_TEXT_RECALL_SIZE = 10_000;
    public static final HybridSearchOptions DEFAULTS = new HybridSearchOptions(DEFAULT_MAX_TEXT_RECALL_SIZE, true);

    /** The options {@code hybridSearch} sets; the defaults when it is absent. */
    public static HybridSearchOptions parse(ObjectNode hybridSearch) {
        if (hybridSearch == null) {
            return DEFAULTS;
        }
        int maxTextRecallSize = DEFAULT_MAX_TEXT_RECALL_SIZE;
        JsonNode size = hybridSearch.get("maxTextRecallSize");
        if (size != null && !size.isNull()) {
            if (!size.isIntegralNumber() || !size.canConvertToInt() || size.intValue() < 1
                    || size.intValue() > MAX_TEXT_RECALL_SIZE) {
                throw SearchException.badRequest("Invalid value '" + size.asText() + "' for 'hybridSearch."
                    + "maxTextRecallSize'. The value needs to be between 1 and " + MAX_TEXT_RECALL_SIZE + ".");
            }
            maxTextRecallSize = size.intValue();
        }
        boolean countAll = true;
        JsonNode mode = hybridSearch.get("countAndFacetMode");
        if (mode != null && !mode.isNull()) {
            String value = mode.isTextual() ? mode.textValue() : mode.asText();
            if ("countRetrievableResults".equalsIgnoreCase(value)) {
                countAll = false;
            } else if (!"countAllResults".equalsIgnoreCase(value)) {
                throw SearchException.badRequest("Invalid value '" + value + "' for 'hybridSearch.countAndFacetMode'. "
                    + "Valid values are 'countRetrievableResults' and 'countAllResults'.");
            }
        }
        return new HybridSearchOptions(maxTextRecallSize, countAll);
    }
}
