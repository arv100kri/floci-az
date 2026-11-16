"""Azure AI Search indexers through ``azure-search-documents``.

A blob data source on floci-az's own Blob service (JSON and text blobs written with the Blob SDK against
the same emulator), indexer runs and status, field mappings, change detection on re-runs, a Cosmos DB data
source on floci-az's Cosmos with the ``_ts`` high-water mark, and a Split skill. The data source
connection strings are the ones these tests hand the Blob and Cosmos SDKs: the indexer runs inside
floci-az, which reaches the emulator's endpoint as the tests do.
"""
import json
import time
import uuid

import pytest
from azure.cosmos import PartitionKey
from azure.search.documents.indexes.models import (
    DataSourceCredentials,
    FieldMapping,
    FieldMappingFunction,
    HighWaterMarkChangeDetectionPolicy,
    IndexingParameters,
    IndexingParametersConfiguration,
    InputFieldMappingEntry,
    OutputFieldMappingEntry,
    SearchField,
    SearchFieldDataType,
    SearchIndex,
    SearchIndexer,
    SearchIndexerDataContainer,
    SearchIndexerDataSourceConnection,
    SearchIndexerSkillset,
    SplitSkill,
    WebApiSkill,
)

from . import _support
from ._support import COLLECTION_OF_STRINGS, eventually, search_keys, status_of

pytestmark = pytest.mark.search_indexer

DEV_STORAGE_KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="
COSMOS_KEY = "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw=="
ACCOUNT = "devstoreaccount1"
BLOB_CONNECTION = (f"DefaultEndpointsProtocol=http;AccountName={ACCOUNT};AccountKey={DEV_STORAGE_KEY};"
                   f"BlobEndpoint={_support.EMULATOR_BASE}/{ACCOUNT};")

# Indexers run asynchronously; Azure documents no bound, so this only caps a hung run.
RUN_TIMEOUT = 120
FINISHED = ("success", "transientFailure")


@pytest.fixture
def resources(index_client, indexer_client, blob_service_client):
    """Tracks what a test creates and deletes it afterwards."""
    created = {"indexers": [], "skillsets": [], "datasources": [], "indexes": [], "containers": []}
    yield created
    for indexer in created["indexers"]:
        _support.delete_quietly(indexer_client.delete_indexer, indexer)
    for skillset in created["skillsets"]:
        _support.delete_quietly(indexer_client.delete_skillset, skillset)
    for datasource in created["datasources"]:
        _support.delete_quietly(indexer_client.delete_data_source_connection, datasource)
    for index in created["indexes"]:
        _support.delete_quietly(index_client.delete_index, index)
    for container in created["containers"]:
        _support.delete_quietly(blob_service_client.delete_container, container)


def _container(blob_service_client, resources):
    container_name = f"search-{uuid.uuid4().hex[:12]}"
    resources["containers"].append(container_name)
    return blob_service_client.create_container(container_name)


def _article(text, date_published, tags):
    return json.dumps({"article": {"text": text, "datePublished": date_published, "tags": tags}})


def _json_index(index_client, resources):
    index_name = _support.name("blobs-json")
    resources["indexes"].append(index_name)
    index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="text", type=SearchFieldDataType.STRING, searchable=True),
        SearchField(name="date", type=SearchFieldDataType.DATE_TIME_OFFSET, filterable=True, sortable=True),
        SearchField(name="tags", type=COLLECTION_OF_STRINGS, filterable=True),
        SearchField(name="fileName", type=SearchFieldDataType.STRING, filterable=True, sortable=True),
    ]))
    return index_name


def _blob_datasource(indexer_client, resources, container_name, folder=None):
    datasource = _support.name("blob-ds")
    resources["datasources"].append(datasource)
    indexer_client.create_data_source_connection(SearchIndexerDataSourceConnection(
        name=datasource, type="azureblob", credentials=DataSourceCredentials(connection_string=BLOB_CONNECTION),
        container=SearchIndexerDataContainer(name=container_name, query=folder)))
    return datasource


JSON_FIELD_MAPPINGS = [
    FieldMapping(source_field_name="metadata_storage_path", target_field_name="id",
                 mapping_function=FieldMappingFunction(name="base64Encode")),
    FieldMapping(source_field_name="metadata_storage_name", target_field_name="fileName"),
    FieldMapping(source_field_name="/article/text", target_field_name="text"),
    FieldMapping(source_field_name="/article/datePublished", target_field_name="date"),
    FieldMapping(source_field_name="/article/tags", target_field_name="tags"),
]


def _json_parsing():
    return IndexingParameters(configuration=IndexingParametersConfiguration(parsing_mode="json"))


def _create_indexer(indexer_client, resources, indexer):
    resources["indexers"].append(indexer.name)
    indexer_client.create_indexer(indexer)
    return indexer.name


def _history_size(indexer_client, indexer):
    return len(indexer_client.get_indexer_status(indexer).execution_history or [])


def _await_run(indexer_client, indexer, previous=None, previous_runs=0):
    """Waits for a finished run newer than ``previous`` (any finished run when None) and returns its result."""
    previous_start = previous.start_time if previous is not None else None

    def finished(status):
        last = status.last_result
        if last is None or last.status not in FINISHED:
            return False
        runs = len(status.execution_history or [])
        return previous is None or last.start_time != previous_start or runs > previous_runs

    status = _support.poll(lambda: indexer_client.get_indexer_status(indexer), finished, RUN_TIMEOUT)
    assert finished(status), f"indexer {indexer} did not finish a new run: {_describe(status.last_result)}"
    return status.last_result


def _run_and_await(indexer_client, indexer, previous):
    runs_before = _history_size(indexer_client, indexer)
    statuses = []
    indexer_client.run_indexer(indexer, raw_response_hook=lambda r: statuses.append(r.http_response.status_code))
    assert statuses == [202]
    return _await_run(indexer_client, indexer, previous, runs_before)


def _describe(result):
    if result is None:
        return "no run yet"
    return (f"status={result.status}, items={result.item_count}, failed={result.failed_item_count}, "
            f"error={result.error_message}, errors={[(e.key, e.error_message) for e in result.errors or []]}")


# https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-storage (type azureblob, container query for
# subfolders, "An indexer runs automatically when it's created")
# https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-json (parsingMode json, JSON Pointer source
# fields)
# https://learn.microsoft.com/azure/search/search-indexer-field-mappings (base64Encode for document keys)
# https://learn.microsoft.com/rest/api/searchservice/indexers/get-status (lastResult, itemsProcessed, itemsFailed)
def test_json_blobs_are_indexed_through_field_mappings(service, index_client, indexer_client,
                                                       blob_service_client, resources):
    container = _container(blob_service_client, resources)
    container.upload_blob("json/a.json", _article("Lighthouse restoration finished", "2016-04-13",
                                                  ["coast", "history"]), overwrite=True)
    container.upload_blob("json/b.json", _article("Harbor festival schedule", "2017-06-01", ["events"]),
                          overwrite=True)
    container.upload_blob("notes/ignored.txt", "outside the json folder", overwrite=True)

    index_name = _json_index(index_client, resources)
    datasource = _blob_datasource(indexer_client, resources, container.container_name, "json")
    fetched_source = indexer_client.get_data_source_connection(datasource)
    assert (fetched_source.type, fetched_source.container.name, fetched_source.container.query) == (
        "azureblob", container.container_name, "json")

    indexer = _create_indexer(indexer_client, resources, SearchIndexer(
        name=_support.name("json-indexer"), data_source_name=datasource, target_index_name=index_name,
        parameters=_json_parsing(), field_mappings=JSON_FIELD_MAPPINGS))
    first = _await_run(indexer_client, indexer)
    assert first.status == "success", _describe(first)
    assert (first.item_count, first.failed_item_count) == (2, 0)
    assert indexer_client.get_indexer_status(indexer).status == "running"

    client = _support.search_client(service, index_name)
    by_file = {r["fileName"]: r for r in client.search(search_text="*")}
    assert set(by_file) == {"a.json", "b.json"}
    assert by_file["a.json"]["text"] == "Lighthouse restoration finished"
    assert by_file["a.json"]["tags"] == ["coast", "history"]
    assert str(by_file["a.json"]["date"]).startswith("2016-04-13")
    assert by_file["b.json"]["text"] == "Harbor festival schedule"
    assert client.get_document(by_file["a.json"]["id"])["text"] == "Lighthouse restoration finished"
    assert [r["fileName"] for r in client.search(search_text="lighthouse")] == ["a.json"]

    fetched = indexer_client.get_indexer(indexer)
    assert len(fetched.field_mappings) == 5
    assert any(m.target_field_name == "id" and m.mapping_function and m.mapping_function.name == "base64Encode"
               for m in fetched.field_mappings)
    assert indexer in indexer_client.get_indexer_names()


# https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-changed-deleted (change detection through the
# built-in timestamps)
# https://learn.microsoft.com/azure/search/search-howto-run-reset-indexers ("If the underlying content is unchanged, a
# run operation has no effect ... 0\0 documents processed"; "Reset Indexers clears the high-water mark and performs a
# full reindex of all documents")
# https://learn.microsoft.com/rest/api/searchservice/indexers/run (202 Accepted)
# https://learn.microsoft.com/rest/api/searchservice/indexers/reset (204 No Content)
def test_reruns_pick_up_only_new_and_changed_blobs_until_a_reset(service, index_client, indexer_client,
                                                                 blob_service_client, resources):
    container = _container(blob_service_client, resources)
    container.upload_blob("json/a.json", _article("first", "2020-01-01", ["one"]), overwrite=True)
    container.upload_blob("json/b.json", _article("second", "2020-01-02", ["two"]), overwrite=True)
    index_name = _json_index(index_client, resources)
    indexer = _create_indexer(indexer_client, resources, SearchIndexer(
        name=_support.name("rerun-indexer"),
        data_source_name=_blob_datasource(indexer_client, resources, container.container_name, "json"),
        target_index_name=index_name, parameters=_json_parsing(), field_mappings=JSON_FIELD_MAPPINGS))
    initial = _await_run(indexer_client, indexer)
    assert initial.item_count == 2, _describe(initial)

    # Blob timestamps can be second-grained; keep each change clearly after the previous run's high-water mark.
    time.sleep(1.1)
    container.upload_blob("json/c.json", _article("third", "2020-01-03", ["three"]), overwrite=True)
    added = _run_and_await(indexer_client, indexer, initial)
    assert (added.status, added.item_count) == ("success", 1), _describe(added)

    unchanged = _run_and_await(indexer_client, indexer, added)
    assert (unchanged.status, unchanged.item_count) == ("success", 0), _describe(unchanged)

    time.sleep(1.1)
    container.upload_blob("json/b.json", _article("second, revised", "2020-01-02", ["two"]), overwrite=True)
    changed = _run_and_await(indexer_client, indexer, unchanged)
    assert (changed.status, changed.item_count) == ("success", 1), _describe(changed)

    client = _support.search_client(service, index_name)
    eventually(lambda: _assert_equal(client.get_document_count(), 3))
    assert [r["fileName"] for r in client.search(search_text="revised")] == ["b.json"]

    statuses = []
    indexer_client.reset_indexer(indexer, raw_response_hook=lambda r: statuses.append(r.http_response.status_code))
    assert statuses == [204]
    full = _run_and_await(indexer_client, indexer, changed)
    assert (full.status, full.item_count) == ("success", 3), _describe(full)


# https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-storage (content and metadata properties map
# to "similarly named and typed fields"; metadata_storage_name "is the file name of the blob";
# indexedFileNameExtensions)
# https://learn.microsoft.com/azure/search/search-indexer-field-mappings (the blob indexer "automatically adds a field
# mapping from metadata_storage_path ... to the index key field if no field mapping is specified")
def test_text_blobs_are_indexed_into_content_with_the_default_key_mapping(service, index_client, indexer_client,
                                                                          blob_service_client, resources):
    container = _container(blob_service_client, resources)
    container.upload_blob("notes/c.txt", "The lighthouse keeper wrote in the log every night.", overwrite=True)
    container.upload_blob("notes/d.txt", "Tide tables for the harbor are posted daily.", overwrite=True)
    container.upload_blob("notes/skip.json", _article("not text", "2020-01-01", []), overwrite=True)

    index_name = _support.name("blobs-text")
    resources["indexes"].append(index_name)
    index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="content", type=SearchFieldDataType.STRING, searchable=True),
        SearchField(name="metadata_storage_name", type=SearchFieldDataType.STRING, filterable=True,
                    sortable=True),
    ]))
    indexer = _create_indexer(indexer_client, resources, SearchIndexer(
        name=_support.name("text-indexer"),
        data_source_name=_blob_datasource(indexer_client, resources, container.container_name),
        target_index_name=index_name,
        parameters=IndexingParameters(configuration=IndexingParametersConfiguration(
            indexed_file_name_extensions=".txt"))))
    run = _await_run(indexer_client, indexer)
    assert (run.status, run.item_count) == ("success", 2), _describe(run)

    client = _support.search_client(service, index_name)
    results = list(client.search(search_text="*"))
    assert {r["metadata_storage_name"] for r in results} == {"c.txt", "d.txt"}
    assert [r["metadata_storage_name"] for r in client.search(search_text="lighthouse")] == ["c.txt"]
    for result in results:
        assert result["id"], "the key is populated without a field mapping"
        assert client.get_document(result["id"])["metadata_storage_name"] == result["metadata_storage_name"]


# https://learn.microsoft.com/azure/search/search-howto-index-cosmosdb (type cosmosdb, "AccountEndpoint=...;AccountKey=
# ...;Database=..." connection string, "Change detection is supported by default through a _ts field" with a
# HighWaterMarkChangeDetectionPolicy). The guide asks for an endpoint without a port; floci-az's has one.
def test_cosmos_db_data_source_picks_up_changes_through_the_timestamp(service, index_client, indexer_client,
                                                                     cosmos_client, resources):
    database_id = f"searchdb{uuid.uuid4().hex[:10]}"
    database = cosmos_client.create_database(database_id)
    try:
        items = database.create_container(id="items", partition_key=PartitionKey(path="/pk"))
        items.create_item({"id": "c1", "pk": "p", "name": "first item"})
        items.create_item({"id": "c2", "pk": "p", "name": "second item"})

        index_name = _support.name("cosmos")
        resources["indexes"].append(index_name)
        index_client.create_index(SearchIndex(name=index_name, fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
            SearchField(name="name", type=SearchFieldDataType.STRING, searchable=True),
        ]))
        datasource = _support.name("cosmos-ds")
        resources["datasources"].append(datasource)
        connection = (f"AccountEndpoint={_support.EMULATOR_BASE}/{ACCOUNT}-cosmos;AccountKey={COSMOS_KEY};"
                      f"Database={database_id}")
        indexer_client.create_data_source_connection(SearchIndexerDataSourceConnection(
            name=datasource, type="cosmosdb", credentials=DataSourceCredentials(connection_string=connection),
            container=SearchIndexerDataContainer(name="items"),
            data_change_detection_policy=HighWaterMarkChangeDetectionPolicy(high_water_mark_column_name="_ts")))
        policy = indexer_client.get_data_source_connection(datasource).data_change_detection_policy
        assert policy.high_water_mark_column_name == "_ts"

        indexer = _create_indexer(indexer_client, resources, SearchIndexer(
            name=_support.name("cosmos-indexer"), data_source_name=datasource, target_index_name=index_name))
        first = _await_run(indexer_client, indexer)
        assert (first.status, first.item_count) == ("success", 2), _describe(first)

        client = _support.search_client(service, index_name)
        eventually(lambda: _assert_equal(set(search_keys(client, "*")), {"c1", "c2"}))

        time.sleep(1.1)  # _ts has one-second resolution; step past the first run's high-water mark
        items.upsert_item({"id": "c1", "pk": "p", "name": "first item renamed"})
        items.create_item({"id": "c3", "pk": "p", "name": "third item"})
        second = _run_and_await(indexer_client, indexer, first)
        assert second.status == "success", _describe(second)
        assert second.item_count >= 2, _describe(second)

        eventually(lambda: _assert_equal(client.get_document_count(), 3))
        assert client.get_document("c1")["name"] == "first item renamed"
        assert search_keys(client, "third") == ["c3"]
    finally:
        try:
            cosmos_client.delete_database(database_id)
        except Exception:  # noqa: BLE001 - cleanup only
            pass


# https://learn.microsoft.com/azure/search/cognitive-search-skill-textsplit (textSplitMode sentences: "terminates at
# sentence-ending punctuation, such as a period, question mark, or exclamation point")
# https://learn.microsoft.com/azure/search/cognitive-search-output-field-mapping (outputFieldMappings)
def test_split_skill_chunks_blob_content_into_sentences(service, index_client, indexer_client,
                                                        blob_service_client, resources):
    container = _container(blob_service_client, resources)
    container.upload_blob("text/story.txt", "First sentence here. Second sentence follows? Third sentence ends!",
                          overwrite=True)
    index_name = _support.name("split")
    resources["indexes"].append(index_name)
    index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="content", type=SearchFieldDataType.STRING, searchable=True),
        SearchField(name="sentences", type=COLLECTION_OF_STRINGS, searchable=True),
    ]))
    skillset = _support.name("split-skills")
    resources["skillsets"].append(skillset)
    indexer_client.create_skillset(SearchIndexerSkillset(name=skillset, skills=[SplitSkill(
        inputs=[InputFieldMappingEntry(name="text", source="/document/content")],
        outputs=[OutputFieldMappingEntry(name="textItems", target_name="sentences")],
        text_split_mode="sentences", context="/document")]))
    assert len(indexer_client.get_skillset(skillset).skills) == 1

    indexer = _create_indexer(indexer_client, resources, SearchIndexer(
        name=_support.name("split-indexer"),
        data_source_name=_blob_datasource(indexer_client, resources, container.container_name, "text"),
        target_index_name=index_name, skillset_name=skillset,
        output_field_mappings=[FieldMapping(source_field_name="/document/sentences",
                                            target_field_name="sentences")]))
    run = _await_run(indexer_client, indexer)
    assert run.status == "success", _describe(run)

    results = list(_support.search_client(service, index_name).search(search_text="*"))
    assert len(results) == 1
    sentences = [s.strip() for s in results[0]["sentences"]]
    assert len(sentences) == 3, sentences
    assert [s.split()[0] for s in sentences] == ["First", "Second", "Third"]


# https://learn.microsoft.com/azure/search/cognitive-search-custom-skill-web-api (uri: "Only the https URI scheme is
# allowed")
# https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("400 Bad Request")
def test_web_api_skill_rejects_a_plain_http_uri(indexer_client, resources):
    skillset = _support.name("webapi-skills")
    resources["skillsets"].append(skillset)
    skill = WebApiSkill(inputs=[InputFieldMappingEntry(name="text", source="/document/content")],
                        outputs=[OutputFieldMappingEntry(name="label", target_name="label")],
                        uri="http://example.invalid/api/label", http_method="POST", context="/document")
    assert status_of(lambda: indexer_client.create_skillset(
        SearchIndexerSkillset(name=skillset, skills=[skill]))) == 400


def _assert_equal(actual, expected):
    assert actual == expected
