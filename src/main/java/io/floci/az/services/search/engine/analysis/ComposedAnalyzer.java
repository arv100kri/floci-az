package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** An analyzer assembled from character filters, a tokenizer and token filters, applied in that order. */
public final class ComposedAnalyzer implements Analyzer {

    private final String name;
    private final List<CharFilter> charFilters;
    private final Tokenizer tokenizer;
    private final List<TokenFilter> tokenFilters;

    public ComposedAnalyzer(String name, List<CharFilter> charFilters, Tokenizer tokenizer,
                            List<TokenFilter> tokenFilters) {
        this.name = name;
        this.charFilters = List.copyOf(charFilters);
        this.tokenizer = tokenizer;
        this.tokenFilters = List.copyOf(tokenFilters);
    }

    public ComposedAnalyzer(String name, Tokenizer tokenizer, List<TokenFilter> tokenFilters) {
        this(name, List.of(), tokenizer, tokenFilters);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Token> analyze(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String filtered = text;
        IntUnaryOperator correction = IntUnaryOperator.identity();
        for (CharFilter charFilter : charFilters) {
            CharFilter.Result result = charFilter.apply(filtered);
            filtered = result.text();
            correction = result.offsetCorrection().andThen(correction);
        }
        List<Token> tokens = tokenizer.tokenize(filtered);
        if (!charFilters.isEmpty()) {
            List<Token> corrected = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                corrected.add(token.withOffsets(correction.applyAsInt(token.startOffset()),
                    correction.applyAsInt(token.endOffset())));
            }
            tokens = corrected;
        }
        for (TokenFilter tokenFilter : tokenFilters) {
            tokens = tokenFilter.filter(tokens);
        }
        return tokens;
    }

    @Override
    public String normalizeTerm(String term) {
        String normalized = term;
        for (CharFilter charFilter : charFilters) {
            normalized = charFilter.apply(normalized).text();
        }
        for (TokenFilter tokenFilter : tokenFilters) {
            normalized = tokenFilter.normalizeTerm(normalized);
        }
        return normalized;
    }
}
