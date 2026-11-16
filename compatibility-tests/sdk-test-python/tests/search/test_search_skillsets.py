"""Azure AI Search skillsets through ``azure-search-documents``.

Create, get, list, update with ETags and delete; the defaults the service adds; the Custom Web API skill's
redacted secrets and placeholders; index projections; and the documented validation of skill definitions.
Reset Skills is a preview operation the stable SDK does not expose.
"""
import pytest
from azure.core import MatchConditions
from azure.core.exceptions import HttpResponseError, ResourceExistsError, ResourceNotFoundError
from azure.search.documents.indexes.models import (
    ConditionalSkill,
    IndexProjectionMode,
    InputFieldMappingEntry,
    OutputFieldMappingEntry,
    SearchField,
    SearchFieldDataType,
    SearchIndex,
    SearchIndexerIndexProjection,
    SearchIndexerIndexProjectionSelector,
    SearchIndexerIndexProjectionsParameters,
    SearchIndexerSkillset,
    ShaperSkill,
    SplitSkill,
    WebApiSkill,
)

from . import _support
from ._support import status_of

pytestmark = pytest.mark.search_indexer


@pytest.fixture
def skillsets(indexer_client):
    """Tracks the skillsets a test creates and deletes them afterwards."""
    created = []
    yield created
    for skillset in created:
        _support.delete_quietly(indexer_client.delete_skillset, skillset)


def _split(name=None, context=None):
    return SplitSkill(name=name, context=context, text_split_mode="pages", maximum_page_length=2000,
                      inputs=[InputFieldMappingEntry(name="text", source="/document/content")],
                      outputs=[OutputFieldMappingEntry(name="textItems", target_name="pages")])


# https://learn.microsoft.com/rest/api/searchservice/skillsets/create (name, description, skills); "A skill with no name
# defined will be given a default name of its 1-based index in the skills array, prefixed with the character '#'";
# context "The default is /document"
def test_skillset_lifecycle_create_get_list_update_delete(indexer_client, skillsets):
    name = _support.name("chunker")
    skillsets.append(name)
    shaper = ShaperSkill(name="shape", context="/document/pages/*",
                         inputs=[InputFieldMappingEntry(name="text", source="/document/pages/*")],
                         outputs=[OutputFieldMappingEntry(name="output", target_name="shaped")])
    created = indexer_client.create_skillset(SearchIndexerSkillset(name=name, description="split and shape",
                                                                   skills=[_split(), shaper]))
    assert created.name == name
    assert created.e_tag

    fetched = indexer_client.get_skillset(name)
    assert [skill.name for skill in fetched.skills] == ["#1", "shape"]
    assert fetched.skills[0].context == "/document"
    assert fetched.skills[0].maximum_page_length == 2000
    assert isinstance(fetched.skills[1], ShaperSkill)
    assert name in indexer_client.get_skillset_names()
    assert name in [skillset.name for skillset in indexer_client.get_skillsets()]

    fetched.description = "updated"
    updated = indexer_client.create_or_update_skillset(fetched)
    assert updated.description == "updated"
    assert updated.e_tag != created.e_tag
    assert indexer_client.get_skillset(name).description == "updated"

    indexer_client.delete_skillset(name)
    with pytest.raises(ResourceNotFoundError):
        indexer_client.get_skillset(name)


# https://learn.microsoft.com/rest/api/searchservice/skillsets/create-or-update (If-Match, If-None-Match)
def test_stale_etags_are_rejected_with_precondition_failed(indexer_client, skillsets):
    name = _support.name("etags")
    skillsets.append(name)
    stale = indexer_client.create_skillset(SearchIndexerSkillset(name=name, skills=[_split()]))
    current = indexer_client.get_skillset(name)
    current.description = "changed"
    indexer_client.create_or_update_skillset(current)

    stale.description = "stale"
    with pytest.raises(HttpResponseError) as error:
        indexer_client.create_or_update_skillset(stale, match_condition=MatchConditions.IfNotModified)
    assert error.value.status_code == 412
    with pytest.raises(HttpResponseError) as error:
        indexer_client.delete_skillset(stale, match_condition=MatchConditions.IfNotModified)
    assert error.value.status_code == 412
    assert indexer_client.get_skillset(name).description == "changed"


def test_creating_an_existing_skillset_is_a_conflict(indexer_client, skillsets):
    name = _support.name("twice")
    skillsets.append(name)
    indexer_client.create_skillset(SearchIndexerSkillset(name=name, skills=[_split()]))
    with pytest.raises(ResourceExistsError):
        indexer_client.create_skillset(SearchIndexerSkillset(name=name, skills=[_split()]))


# https://learn.microsoft.com/azure/search/cognitive-search-custom-skill-web-api ("GET and LIST responses redact
# sensitive query values, including sig and code", "<redacted> for all header values", "set the whole uri to
# <unchanged>")
def test_web_api_secrets_are_redacted_and_kept_through_placeholders(indexer_client, skillsets):
    name = _support.name("webapi")
    skillsets.append(name)
    skill = WebApiSkill(name="lookup", uri="https://fn.example.net/api/lookup?code=s3cret&mode=fast",
                        http_headers={"x-api-key": "k1"}, http_method="POST",
                        inputs=[InputFieldMappingEntry(name="text", source="/document/content")],
                        outputs=[OutputFieldMappingEntry(name="result", target_name="result")])
    indexer_client.create_skillset(SearchIndexerSkillset(name=name, skills=[skill]))

    fetched = indexer_client.get_skillset(name)
    assert fetched.skills[0].uri == "https://fn.example.net/api/lookup?code=<redacted>&mode=fast"
    assert fetched.skills[0].http_headers == {"x-api-key": "<redacted>"}

    fetched.description = "round trip"
    indexer_client.create_or_update_skillset(fetched)
    fetched = indexer_client.get_skillset(name)
    assert fetched.description == "round trip"
    assert fetched.skills[0].uri == "https://fn.example.net/api/lookup?code=<redacted>&mode=fast"

    fetched.skills[0].uri = "https://other.example.net/api/lookup?code=<redacted>&mode=fast"
    assert status_of(lambda: indexer_client.create_or_update_skillset(fetched)) == 400


# https://learn.microsoft.com/azure/search/search-how-to-define-index-projections (selectors, parentKeyFieldName,
# sourceContext, mappings, projectionMode)
def test_index_projections_round_trip_and_need_their_target_index(index_client, indexer_client, skillsets):
    index_name = _support.name("chunks")
    index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="chunk_id", type=SearchFieldDataType.STRING, key=True, analyzer_name="keyword"),
        SearchField(name="parent_id", type=SearchFieldDataType.STRING, filterable=True),
        SearchField(name="chunk", type=SearchFieldDataType.STRING, searchable=True),
    ]))
    try:
        name = _support.name("rag")
        skillsets.append(name)
        projections = SearchIndexerIndexProjection(
            selectors=[SearchIndexerIndexProjectionSelector(
                target_index_name=index_name, parent_key_field_name="parent_id", source_context="/document/pages/*",
                mappings=[InputFieldMappingEntry(name="chunk", source="/document/pages/*")])],
            parameters=SearchIndexerIndexProjectionsParameters(
                projection_mode=IndexProjectionMode.SKIP_INDEXING_PARENT_DOCUMENTS))
        indexer_client.create_skillset(SearchIndexerSkillset(name=name, skills=[_split()],
                                                             index_projection=projections))
        fetched = indexer_client.get_skillset(name)
        selector = fetched.index_projection.selectors[0]
        assert selector.target_index_name == index_name
        assert selector.parent_key_field_name == "parent_id"
        assert fetched.index_projection.parameters.projection_mode == "skipIndexingParentDocuments"

        projections.selectors[0].target_index_name = "missing-index"
        missing = _support.name("rag")
        skillsets.append(missing)
        assert status_of(lambda: indexer_client.create_skillset(
            SearchIndexerSkillset(name=missing, skills=[_split()], index_projection=projections))) == 400
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


# https://learn.microsoft.com/azure/search/cognitive-search-skill-conditional (evaluated fields)
# https://learn.microsoft.com/azure/search/cognitive-search-defining-skillset (skill names, outputs)
def test_invalid_definitions_are_bad_requests(indexer_client, skillsets):
    name = _support.name("invalid")
    skillsets.append(name)
    duplicate_names = [_split(name="same"), _split(name="same")]
    assert status_of(lambda: indexer_client.create_skillset(
        SearchIndexerSkillset(name=name, skills=duplicate_names))) == 400
    unknown_output = SplitSkill(inputs=[InputFieldMappingEntry(name="text", source="/document/content")],
                                outputs=[OutputFieldMappingEntry(name="pages")])
    assert status_of(lambda: indexer_client.create_skillset(
        SearchIndexerSkillset(name=name, skills=[unknown_output]))) == 400
    broken_expression = ConditionalSkill(
        inputs=[InputFieldMappingEntry(name="condition", source="= $(/document/language) =="),
                InputFieldMappingEntry(name="whenTrue", source="= 'en'"),
                InputFieldMappingEntry(name="whenFalse", source="= null")],
        outputs=[OutputFieldMappingEntry(name="output", target_name="language")])
    assert status_of(lambda: indexer_client.create_skillset(
        SearchIndexerSkillset(name=name, skills=[broken_expression]))) == 400
    with pytest.raises(ResourceNotFoundError):
        indexer_client.get_skillset(name)
