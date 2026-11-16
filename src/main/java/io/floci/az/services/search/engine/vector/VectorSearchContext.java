package io.floci.az.services.search.engine.vector;

import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.vector.VectorSearchConfig.Vectorizer;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The vector side of one query: its validated vector queries with every query vector resolved, and the ranked lists
 * vector retrieval produces for fusion.
 *
 * <p>{@link #prepare} runs before the query takes the index's read lock: it validates the vector queries and calls the
 * vectorizers of {@code text}, {@code imageUrl} and {@code imageBinary} queries (once per vectorizer and query), so a
 * slow embedding endpoint never holds up indexing.</p>
 */
public final class VectorSearchContext {

    private final List<VectorQuery> queries;
    private final Map<String, float[]> vectors;
    private final List<VectorList> lists = new ArrayList<>();

    private VectorSearchContext(List<VectorQuery> queries, Map<String, float[]> vectors) {
        this.queries = List.copyOf(queries);
        this.vectors = Map.copyOf(vectors);
    }

    /** The context of a request without vector queries. */
    public static VectorSearchContext empty() {
        return new VectorSearchContext(List.of(), Map.of());
    }

    /** Validates the vector queries of {@code request} and vectorizes the ones that need it, through {@code client}. */
    public static VectorSearchContext prepare(SearchIndexDefinition definition, SearchRequest request,
                                              EngineSettings settings, VectorizerClient client) {
        if (request.vectorQueries().isEmpty()) {
            return empty();
        }
        VectorSearchConfig config = VectorSearchConfig.of(definition);
        List<VectorQuery> queries = VectorQueryParser.parse(request, definition, config);
        VectorFilterMode.parse(request.vectorFilterMode());
        HybridSearchOptions.parse(request.hybridSearch());
        Map<String, float[]> vectors = new HashMap<>();
        for (VectorQuery query : queries) {
            if (!query.needsVectorizer()) {
                continue;
            }
            Map<String, float[]> byVectorizer = new HashMap<>();
            for (SearchFieldDefinition field : query.fields()) {
                Vectorizer vectorizer = config.settings(field).vectorizer();
                if (vectorizer == null) {
                    throw SearchException.badRequest("The vector query vectorQueries[" + query.position() + "] of "
                        + "kind '" + query.kind().wireName() + "' searches the field '" + field.path() + "', whose "
                        + "vector search profile '" + field.vectorSearchProfile() + "' has no vectorizer. Configure a "
                        + "vectorizer on the profile, or send the query vector with kind 'vector'.");
                }
                float[] vector = byVectorizer.get(vectorizer.name());
                if (vector == null) {
                    vector = client.embed(vectorizer, VectorInput.of(query), field.dimensions(),
                        settings.modelEndpointUrl());
                    byVectorizer.put(vectorizer.name(), vector);
                }
                VectorQueryParser.checkVector(vector, field);
                vectors.put(key(query, field), vector);
            }
        }
        return new VectorSearchContext(queries, vectors);
    }

    private static String key(VectorQuery query, SearchFieldDefinition field) {
        return query.position() + "/" + field.path();
    }

    /** Whether the request has vector queries. */
    public boolean active() {
        return !queries.isEmpty();
    }

    /** The vector queries, in request order. */
    public List<VectorQuery> queries() {
        return queries;
    }

    /** The vector {@code query} searches {@code field} with: its own, or what the field's vectorizer returned. */
    public float[] vector(VectorQuery query, SearchFieldDefinition field) {
        return query.vector() != null ? query.vector() : vectors.get(key(query, field));
    }

    /** The ranked lists of the vector queries, one per query and field; vector retrieval fills it. */
    public List<VectorList> lists() {
        return lists;
    }
}
