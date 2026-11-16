package io.floci.az.services.search.engine.analysis;

import java.util.List;

/** Splits text into tokens; the first stage of a {@link ComposedAnalyzer}. Implementations are thread-safe. */
public interface Tokenizer {

    /** The tokens of {@code text}, positions counting from 0, offsets into {@code text}. */
    List<Token> tokenize(String text);
}
