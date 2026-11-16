package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.vector.VectorSearchContext;
import io.floci.az.services.search.engine.vector.VectorizerClient;
import io.floci.az.services.search.model.SearchRequest;

import java.util.List;

/**
 * Runs a query through its stages, in order, inside the index's read lock. Immutable and shared.
 *
 * <p>The standard order: filter, permission trimming, text retrieval, vector retrieval, fusion, scoring profile, facets,
 * semantic ranking, sort and page, highlights. A stage is replaced by changing its class, not this order; add a stage
 * here only when a new step appears.</p>
 */
public final class SearchPipeline {

    private final List<SearchStage> stages;
    private final SuggestStage suggestStage;
    private final VectorizerClient vectorizers;

    public SearchPipeline(List<SearchStage> stages, SuggestStage suggestStage) {
        this(stages, suggestStage, new VectorizerClient());
    }

    public SearchPipeline(List<SearchStage> stages, SuggestStage suggestStage, VectorizerClient vectorizers) {
        this.stages = List.copyOf(stages);
        this.suggestStage = suggestStage;
        this.vectorizers = vectorizers;
    }

    /** The standard pipeline, with a vectorizer client of its own. */
    public static SearchPipeline standard() {
        return standard(new VectorizerClient());
    }

    /** The standard pipeline, calling vectorizers through {@code vectorizers}. */
    public static SearchPipeline standard(VectorizerClient vectorizers) {
        return new SearchPipeline(List.of(
            new FilterStage(),
            new PermissionTrimmingStage(),
            new TextRetrievalStage(),
            new VectorRetrievalStage(),
            new FusionStage(),
            new ScoringProfileStage(),
            new FacetStage(),
            new SemanticStage(),
            new SortAndPageStage(),
            new HighlightStage()
        ), new InfixSuggestStage(), vectorizers);
    }

    public List<SearchStage> stages() {
        return stages;
    }

    /** The suggest and autocomplete implementation. */
    public SuggestStage suggestStage() {
        return suggestStage;
    }

    /** Runs {@code request} over {@code index}. */
    public SearchOutcome run(LiveIndex index, SearchRequest request, EngineSettings settings) {
        VectorSearchContext vectors = VectorSearchContext.prepare(index.definition(), request, settings, vectorizers);
        return index.read(live -> {
            SearchExecution execution = new SearchExecution(request, live, settings);
            execution.vectorSearch(vectors);
            for (SearchStage stage : stages) {
                stage.prepare(execution);
            }
            for (SearchStage stage : stages) {
                stage.apply(execution);
            }
            return execution.outcome();
        });
    }
}
