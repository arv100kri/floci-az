package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchException;

/**
 * <b>STUB, to be replaced by suggesters and autocomplete</b> (infix prefix matching over the suggester's source
 * fields, fuzzy matching, and the {@code oneTerm}, {@code twoTerms} and {@code oneTermWithContext} modes). Today both
 * operations answer an Azure-shaped 501 {@code NotImplemented}.
 */
public final class NotImplementedSuggestStage implements SuggestStage {

    @Override
    public ObjectNode suggest(LiveIndex index, ObjectNode parameters) {
        throw SearchException.notImplemented("Suggest (docs/search.suggest)");
    }

    @Override
    public ObjectNode autocomplete(LiveIndex index, ObjectNode parameters) {
        throw SearchException.notImplemented("Autocomplete (docs/search.autocomplete)");
    }
}
