"""Azure AI Search semantic ranking through ``azure-search-documents``.

floci-az answers semantic queries with a deterministic stand-in for the semantic ranker's model, so these
tests check the documented shapes (reranker scores from 0 to 4, extractive captions and answers, the
semantic configuration round trip, the 400s) and the stand-in's documented order, not Azure's own scores.
"""
import pytest
from azure.search.documents.indexes.models import (
    SemanticConfiguration,
    SemanticField,
    SemanticPrioritizedFields,
    SemanticSearch,
)

from . import _support
from ._support import keys, status_of

pytestmark = pytest.mark.search_query

STAND_IN_HEADER = "x-floci-az-semantic-ranker"


@pytest.fixture(scope="module")
def semantic_hotels(service, index_client):
    """The six-hotel corpus with a default semantic configuration: name, description, tags."""
    index_name = _support.name("semantic")
    index = _support.hotels_index(index_name)
    index.semantic_search = SemanticSearch(
        default_configuration_name="hotel-config",
        configurations=[SemanticConfiguration(
            name="hotel-config",
            prioritized_fields=SemanticPrioritizedFields(
                title_field=SemanticField(field_name="name"),
                content_fields=[SemanticField(field_name="description")],
                keywords_fields=[SemanticField(field_name="tags")]))])
    index_client.create_index(index)
    client = _support.search_client(service, index_name)
    client.upload_documents(_support.HOTELS)
    _support.eventually(lambda: _assert_count(client, len(_support.HOTELS)))
    yield index_name
    _support.delete_quietly(index_client.delete_index, index_name)


@pytest.fixture(scope="module")
def hotels(service, semantic_hotels):
    return _support.search_client(service, semantic_hotels)


def _assert_count(client, expected):
    assert client.get_document_count() == expected


def _semantic(client, search_text, **kwargs):
    return client.search(search_text=search_text, query_type="semantic",
                         semantic_configuration_name="hotel-config", **kwargs)


# https://learn.microsoft.com/azure/search/semantic-how-to-configure (a semantic configuration: a title field,
# content fields and keyword fields, plus an optional default)
def test_the_semantic_configuration_round_trips(index_client, semantic_hotels):
    semantic = index_client.get_index(semantic_hotels).semantic_search
    assert semantic.default_configuration_name == "hotel-config"
    fields = semantic.configurations[0].prioritized_fields
    assert fields.title_field.field_name == "name"
    assert [f.field_name for f in fields.content_fields] == ["description"]
    assert [f.field_name for f in fields.keywords_fields] == ["tags"]


# https://learn.microsoft.com/azure/search/semantic-search-overview (@search.rerankerScore ranges from 4 to 0, and
# results are listed in descending order by it); the stand-in ranks the prioritized fields' share of query terms
def test_semantic_queries_rerank_with_reranker_scores(hotels):
    headers = {}
    results = list(_semantic(hotels, "which hotel has a rooftop pool?",
                             raw_response_hook=lambda response: headers.update(response.http_response.headers)))
    assert headers.get(STAND_IN_HEADER) == "stand-in"
    assert keys(results)[:3] == ["3", "1", "6"]
    scores = [result["@search.reranker_score"] for result in results]
    assert all(0 <= score <= 4 for score in scores)
    assert scores == sorted(scores, reverse=True)
    again = list(_semantic(hotels, "which hotel has a rooftop pool?"))
    assert [result["@search.reranker_score"] for result in again] == scores, "the stand-in is deterministic"


# https://learn.microsoft.com/azure/search/semantic-how-to-query-request (captions: "extractive", highlighting
# enabled by default with highlightPreTag/highlightPostTag, "extractive|highlight-false" without highlights)
def test_captions_carry_text_and_highlights(hotels):
    results = list(_semantic(hotels, "hotel with a rooftop pool", query_caption="extractive"))
    caption = results[0]["@search.captions"][0]
    assert results[0]["id"] == "3"
    assert caption.text == "Modern hotel in the city center with a rooftop pool"
    assert "<em>rooftop pool</em>" in caption.highlights

    plain = list(_semantic(hotels, "hotel with a rooftop pool", query_caption="extractive",
                           query_caption_highlight_enabled=False))
    assert plain[0]["@search.captions"][0].highlights is None

    tagged = list(_semantic(hotels, "rooftop pool", query_caption="extractive", highlight_pre_tag="<b>",
                            highlight_post_tag="</b>"))
    assert "<b>rooftop pool</b>" in tagged[0]["@search.captions"][0].highlights


# https://learn.microsoft.com/azure/search/semantic-answers (answers: key, text, highlights and score; count up to
# 10; only for queries that read as questions, otherwise "@search.answers": [])
def test_answers_come_from_questions(hotels):
    results = _semantic(hotels, "which hotel has a rooftop pool?", query_answer="extractive", query_answer_count=3)
    answers = results.get_answers()
    assert len(answers) == 1
    assert answers[0].key == "3"
    assert answers[0].text == "Modern hotel in the city center with a rooftop pool"
    assert "<em>" in answers[0].highlights
    assert answers[0].score >= 0.7

    lenient = _semantic(hotels, "which hotel has a rooftop pool?", query_answer="extractive",
                        query_answer_count=2, query_answer_threshold=0.3).get_answers()
    assert [answer.key for answer in lenient] == ["3", "1"]

    assert _semantic(hotels, "rooftop pool", query_answer="extractive").get_answers() == []


# https://learn.microsoft.com/azure/search/semantic-how-to-query-request (semanticQuery reranks a simple or full
# query with its own plain text)
def test_a_semantic_query_reranks_another_query_type(hotels):
    results = list(hotels.search(search_text="pool", semantic_query="rooftop pool in the city",
                                 semantic_configuration_name="hotel-config"))
    assert keys(results) == ["3", "1"]
    assert all(result["@search.reranker_score"] is not None for result in results)


# https://learn.microsoft.com/azure/search/semantic-how-to-query-request ("adding an orderby clause results in an
# HTTP 400 error if you apply semantic ranking over ordered results"; the configuration must exist)
def test_invalid_semantic_queries_are_rejected(hotels):
    assert status_of(lambda: list(_semantic(hotels, "pool", order_by=["rating desc"]))) == 400
    assert status_of(lambda: list(hotels.search(search_text="pool", query_type="semantic",
                                                semantic_configuration_name="missing"))) == 400
    assert status_of(lambda: list(_semantic(hotels, "pool", semantic_max_wait_in_milliseconds=100))) == 400
