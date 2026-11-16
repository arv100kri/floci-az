"""Azure AI Search vector search through ``azure-search-documents``.

Vector fields with exhaustive KNN and HNSW profiles, k-nearest-neighbour queries, filtered vector
queries, hybrid queries and multiple vector queries. The three-dimensional vectors are chosen so the
cosine similarities to the query vectors are distinct, and HNSW fields are queried with
``exhaustive: true``, which the docs define as an exact search.
"""
import uuid

import pytest
from azure.core.exceptions import HttpResponseError
from azure.search.documents.indexes.models import (
    AzureOpenAIVectorizer,
    AzureOpenAIVectorizerParameters,
    ExhaustiveKnnAlgorithmConfiguration,
    ExhaustiveKnnParameters,
    HnswAlgorithmConfiguration,
    HnswParameters,
    SearchField,
    SearchFieldDataType,
    SearchIndex,
    VectorSearch,
    VectorSearchProfile,
)
from azure.search.documents.models import VectorizableTextQuery, VectorizedQuery

from . import _support
from ._support import eventually, keys

pytestmark = pytest.mark.search_vector

# Built from the enum's value: the shared helper's f-string spells the member's qualified name on Python 3.12+.
COLLECTION_OF_SINGLES = f"Collection({SearchFieldDataType.SINGLE.value})"

X_AXIS = [1.0, 0.0, 0.0]

DOCUMENTS = [
    {"id": "1", "title": "red apple", "category": "fruit",
     "titleVector": [1.0, 0.0, 0.0], "contentVector": [1.0, 0.0, 0.0]},
    {"id": "2", "title": "green apple", "category": "fruit",
     "titleVector": [0.9, 0.1, 0.0], "contentVector": [0.0, 1.0, 0.0]},
    {"id": "3", "title": "blue car", "category": "vehicle",
     "titleVector": [0.2, 1.0, 0.0], "contentVector": [0.9, 0.1, 0.0]},
    {"id": "4", "title": "red car", "category": "vehicle",
     "titleVector": [0.0, 0.0, 1.0], "contentVector": [0.0, 0.0, 1.0]},
    {"id": "5", "title": "yellow banana", "category": "fruit",
     "titleVector": [0.5, 0.5, 0.0], "contentVector": [0.3, 0.3, 0.9]},
]


@pytest.fixture(scope="module")
def vectors(service, index_client):
    index_name = _support.name("vectors")
    index_client.create_index(SearchIndex(
        name=index_name,
        fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
            SearchField(name="title", type=SearchFieldDataType.STRING, searchable=True),
            SearchField(name="category", type=SearchFieldDataType.STRING, filterable=True),
            SearchField(name="titleVector", type=COLLECTION_OF_SINGLES, searchable=True,
                        vector_search_dimensions=3, vector_search_profile_name="exhaustive-profile"),
            SearchField(name="contentVector", type=COLLECTION_OF_SINGLES, searchable=True,
                        vector_search_dimensions=3, vector_search_profile_name="hnsw-profile"),
        ],
        vector_search=VectorSearch(
            algorithms=[
                ExhaustiveKnnAlgorithmConfiguration(
                    name="eknn", parameters=ExhaustiveKnnParameters(metric="cosine")),
                HnswAlgorithmConfiguration(
                    name="hnsw", parameters=HnswParameters(metric="cosine", m=4, ef_construction=400,
                                                           ef_search=500)),
            ],
            profiles=[
                VectorSearchProfile(name="exhaustive-profile", algorithm_configuration_name="eknn"),
                VectorSearchProfile(name="hnsw-profile", algorithm_configuration_name="hnsw"),
            ],
        ),
    ))
    client = _support.search_client(service, index_name)
    client.upload_documents(DOCUMENTS)
    eventually(lambda: _assert_equal(client.get_document_count(), len(DOCUMENTS)))
    yield index_name, client
    _support.delete_quietly(index_client.delete_index, index_name)


def _query(vector, fields, k, **kwargs):
    return VectorizedQuery(vector=vector, fields=fields, k_nearest_neighbors=k, exhaustive=True, **kwargs)


# https://learn.microsoft.com/azure/search/vector-search-how-to-create-index (Collection(Edm.Single) fields with
# dimensions and a vectorSearchProfile; profiles name an hnsw or exhaustiveKnn configuration with a metric)
def test_vector_fields_and_profiles_round_trip(index_client, vectors):
    index = index_client.get_index(vectors[0])
    algorithms = {a.name: a for a in index.vector_search.algorithms}
    assert isinstance(algorithms["eknn"], ExhaustiveKnnAlgorithmConfiguration)
    assert isinstance(algorithms["hnsw"], HnswAlgorithmConfiguration)
    assert algorithms["eknn"].parameters.metric == "cosine"
    assert algorithms["hnsw"].parameters.metric == "cosine"
    assert algorithms["hnsw"].parameters.m == 4
    assert {p.name: p.algorithm_configuration_name for p in index.vector_search.profiles} == {
        "exhaustive-profile": "eknn", "hnsw-profile": "hnsw"}
    title_vector = next(f for f in index.fields if f.name == "titleVector")
    assert (title_vector.vector_search_dimensions, title_vector.vector_search_profile_name) == (
        3, "exhaustive-profile")


# https://learn.microsoft.com/azure/search/vector-search-how-to-query ("Vector queries always return k results")
# https://learn.microsoft.com/azure/search/vector-search-ranking (cosine @search.score range "0.333 - 1.00"; "Score
# values always decrease as the similarity gets worse")
def test_vector_query_returns_k_nearest_neighbours_in_similarity_order(vectors):
    results = list(vectors[1].search(vector_queries=[_query(X_AXIS, "titleVector", 3)]))
    assert keys(results) == ["1", "2", "5"]
    scores = [r["@search.score"] for r in results]
    assert all(0.333 <= s <= 1.0 + 1e-6 for s in scores), scores
    assert scores == sorted(scores, reverse=True)


# https://learn.microsoft.com/azure/search/vector-search-how-to-query ("always return k results, even if the nearest
# neighbors aren't that similar"; "If k is larger than the number of documents in the index, the number of documents
# determines the upper limit")
def test_vector_query_always_returns_k_results_up_to_the_document_count(vectors):
    dissimilar = keys(vectors[1].search(vector_queries=[_query([0.0, 0.0, 1.0], "titleVector", 2)]))
    assert len(dissimilar) == 2 and dissimilar[0] == "4"
    assert set(keys(vectors[1].search(vector_queries=[_query(X_AXIS, "titleVector", 50)]))) == {
        "1", "2", "3", "4", "5"}


# https://learn.microsoft.com/azure/search/vector-search-how-to-query ("vectorQueries.exhaustive (optional) invokes
# exhaustive KNN at query time, even if the field is indexed for HNSW")
def test_exhaustive_query_on_an_hnsw_field_is_exact(vectors):
    assert keys(vectors[1].search(vector_queries=[_query(X_AXIS, "contentVector", 2)])) == ["1", "3"]


# https://learn.microsoft.com/azure/search/vector-search-filters (preFilter: "the filter is applied before the search
# engine runs the query")
def test_filters_restrict_vector_candidates(vectors):
    pre = keys(vectors[1].search(vector_queries=[_query(X_AXIS, "titleVector", 2)],
                                 filter="category eq 'vehicle'", vector_filter_mode="preFilter"))
    assert pre == ["3", "4"]
    post = keys(vectors[1].search(vector_queries=[_query(X_AXIS, "titleVector", 5)],
                                  filter="category eq 'fruit'", vector_filter_mode="postFilter"))
    assert set(post) <= {"1", "2", "5"}, post


# https://learn.microsoft.com/azure/search/hybrid-search-ranking ("documents appearing in the top positions across
# multiple search methods ... should be ranked higher"; RRF sums 1/(rank + k) over the result sets)
def test_hybrid_query_fuses_text_and_vector_results(vectors):
    hybrid = keys(vectors[1].search(search_text="red apple", search_fields=["title"],
                                    vector_queries=[_query(X_AXIS, "titleVector", 3)]))
    assert hybrid[:2] == ["1", "2"], hybrid
    assert set(hybrid) == {"1", "2", "4", "5"}


# https://learn.microsoft.com/azure/search/vector-search-how-to-query (multiple vector queries are "scored using
# reciprocal rank fusion"; "The vector query executes against each vector field that you provide in the fields list")
def test_multiple_vector_queries_and_fields_are_fused(vectors):
    two_queries = keys(vectors[1].search(vector_queries=[
        _query(X_AXIS, "titleVector", 3), _query(X_AXIS, "contentVector", 3)]))
    assert two_queries[:2] == ["1", "5"], two_queries
    assert set(two_queries) == {"1", "2", "3", "5"}

    two_fields = keys(vectors[1].search(vector_queries=[_query(X_AXIS, "titleVector,contentVector", 3)]))
    assert two_fields[:2] == ["1", "5"], two_fields
    assert set(two_fields) == {"1", "2", "3", "5"}


# https://learn.microsoft.com/azure/search/vector-search-how-to-query#vector-weighting (a query's weight multiplies its
# reciprocal rank scores when the lists are fused)
def test_weights_multiply_each_lists_reciprocal_rank_score(vectors):
    weighted = keys(vectors[1].search(vector_queries=[
        _query(X_AXIS, "titleVector", 2), _query(X_AXIS, "contentVector", 2, weight=3.0)]))
    assert weighted == ["1", "3", "2"], weighted


# https://learn.microsoft.com/azure/search/vector-search-how-to-configure-vectorizer (a text query is vectorized by
# the vectorizer of the field's profile); a vectorizer that cannot reach its model fails the query with "Could not
# complete vectorization action" (https://github.com/Azure/azure-search-vector-samples/issues/133)
def test_text_queries_go_through_the_profiles_vectorizer(service, index_client):
    index_name = _support.name("vectorizer")
    index_client.create_index(SearchIndex(
        name=index_name,
        fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
            SearchField(name="embedding", type=COLLECTION_OF_SINGLES, searchable=True,
                        vector_search_dimensions=3, vector_search_profile_name="with-vectorizer"),
        ],
        vector_search=VectorSearch(
            algorithms=[HnswAlgorithmConfiguration(name="graph")],
            profiles=[VectorSearchProfile(name="with-vectorizer", algorithm_configuration_name="graph",
                                          vectorizer_name="aoai")],
            vectorizers=[AzureOpenAIVectorizer(vectorizer_name="aoai", parameters=AzureOpenAIVectorizerParameters(
                resource_url=f"https://floci{uuid.uuid4().hex[:12]}.openai.azure.com", deployment_name="embed",
                model_name="text-embedding-3-small"))],
        ),
    ))
    try:
        fetched = index_client.get_index(index_name)
        assert fetched.vector_search.profiles[0].vectorizer_name == "aoai"
        assert fetched.vector_search.vectorizers[0].parameters.deployment_name == "embed"
        client = _support.search_client(service, index_name)
        with pytest.raises(HttpResponseError) as failure:
            list(client.search(vector_queries=[
                VectorizableTextQuery(text="red fruit", k_nearest_neighbors=1, fields="embedding")]))
        assert "Could not complete vectorization action" in str(failure.value)
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


def _assert_equal(actual, expected):
    assert actual == expected
