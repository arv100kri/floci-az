"""Azure AI Search MVP compatibility through ``azure-search-documents``.

Index management with ETags, document batches (upload, merge, mergeOrUpload, delete) including a 207
partial failure, lookup with select, document count, the simple query syntax with searchMode,
searchFields, OData filters (comparisons, and/or/not, search.in, any/all, geo.distance), orderby with
search.score(), select/top/skip/count, paging past 50 results, and query-key authorization.

Every assertion encodes behavior documented on Microsoft Learn; the URL above each test names the page.
"""
import pytest
from azure.core import MatchConditions
from azure.search.documents import IndexDocumentsBatch
from azure.search.documents.indexes.models import SearchField, SearchFieldDataType, SearchIndex

from . import _support
from ._support import SEATTLE, eventually, search_keys, status_of

pytestmark = pytest.mark.search_mvp


# https://learn.microsoft.com/rest/api/searchservice/indexes/create (201 Created)
# https://learn.microsoft.com/rest/api/searchservice/indexes/create-or-update (200 OK on update)
# https://learn.microsoft.com/rest/api/searchservice/indexes/delete (204 No Content, 404 Not Found)
# https://learn.microsoft.com/azure/search/search-howto-concurrency ("Every time you update a resource, its ETag
# changes automatically")
# https://github.com/Azure/azure-sdk-for-python/blob/main/sdk/search/azure-search-documents/azure/search/documents/indexes/_operations/_operations.py
# (_delete_index raises only for a status "not in [204, 404]": deleting a missing index succeeds)
def test_index_lifecycle_create_get_list_update_delete(index_client):
    index_name = _support.name("lifecycle")
    created = index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="title", type=SearchFieldDataType.STRING, searchable=True),
    ]))
    try:
        assert created.name == index_name
        assert created.e_tag, "a created index carries an ETag"

        fetched = index_client.get_index(index_name)
        assert [f.name for f in fetched.fields] == ["id", "title"]
        assert fetched.fields[0].key is True
        assert fetched.e_tag == created.e_tag

        assert index_name in [index.name for index in index_client.list_indexes()]
        assert index_name in list(index_client.list_index_names())

        fetched.fields.append(SearchField(name="added", type=SearchFieldDataType.INT32, filterable=True))
        updated = index_client.create_or_update_index(fetched)
        assert updated.e_tag != created.e_tag, "an update changes the ETag"
        assert "added" in [f.name for f in index_client.get_index(index_name).fields]
    finally:
        index_client.delete_index(index_name)

    assert status_of(lambda: index_client.get_index(index_name)) == 404
    index_client.delete_index(index_name)
    assert index_client.delete_index(index_name, cls=lambda response, *_: response.http_response.status_code) == 404


# https://learn.microsoft.com/azure/search/search-howto-concurrency (an update whose ETag no longer matches fails)
# https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("412 Precondition Failed: Returned when an
# If-Match or If-None-Match header's condition evaluates to false")
def test_stale_etags_are_rejected_with_precondition_failed(index_client):
    index_name = _support.name("etag")
    original = index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
    ]))
    try:
        client_a = index_client.get_index(index_name)
        client_a.fields.append(SearchField(name="a", type=SearchFieldDataType.INT32))
        index_client.create_or_update_index(client_a, match_condition=MatchConditions.IfNotModified)

        client_b = index_client.get_index(index_name)
        client_b.fields.append(SearchField(name="b", type=SearchFieldDataType.BOOLEAN))
        client_b.e_tag = original.e_tag
        assert status_of(lambda: index_client.create_or_update_index(
            client_b, match_condition=MatchConditions.IfNotModified)) == 412
        assert status_of(lambda: index_client.create_or_update_index(
            client_b, match_condition=MatchConditions.IfMissing)) == 412, \
            "If-None-Match: * only creates, so it fails for an index that exists"
        assert status_of(lambda: index_client.delete_index(
            original, match_condition=MatchConditions.IfNotModified)) == 412
        assert "b" not in [f.name for f in index_client.get_index(index_name).fields]
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


# https://learn.microsoft.com/rest/api/searchservice/documents/index (IndexActionType: upload replaces all fields,
# merge updates an existing document, mergeOrUpload, delete; IndexingResult.statusCode: 200 update or delete,
# 201 created)
# https://learn.microsoft.com/azure/search/search-how-to-load-search-index ("Delete operations are idempotent")
def test_upload_merge_merge_or_upload_and_delete_actions(service, index_client):
    index_name = _support.name("actions")
    index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="name", type=SearchFieldDataType.STRING, searchable=True),
        SearchField(name="category", type=SearchFieldDataType.STRING, filterable=True),
        SearchField(name="rating", type=SearchFieldDataType.INT32, filterable=True),
    ]))
    try:
        client = _support.search_client(service, index_name)
        created = client.upload_documents([{"id": "a", "name": "Alpha", "category": "One", "rating": 1}])
        assert [(r.key, r.succeeded, r.status_code) for r in created] == [("a", True, 201)]

        merged = client.merge_documents([{"id": "a", "rating": 5}])
        assert merged[0].status_code == 200
        after_merge = client.get_document("a")
        assert after_merge["name"] == "Alpha", "merge keeps the fields it does not name"
        assert after_merge["rating"] == 5

        upserts = {r.key: r.status_code for r in client.merge_or_upload_documents(
            [{"id": "b", "name": "Beta"}, {"id": "a", "category": "Two"}])}
        assert upserts == {"b": 201, "a": 200}
        after_upsert = client.get_document("a")
        assert after_upsert["name"] == "Alpha"
        assert after_upsert["category"] == "Two"

        replaced = client.upload_documents([{"id": "a", "name": "Alpha 2"}])
        assert replaced[0].status_code == 200, "upload of an existing key is an update"
        after_replace = client.get_document("a")
        assert after_replace["name"] == "Alpha 2"
        assert after_replace.get("category") is None, "upload replaces every field"
        assert after_replace.get("rating") is None

        deletes = {r.key: (r.succeeded, r.status_code) for r in client.delete_documents(
            [{"id": "b"}, {"id": "never-existed"}])}
        assert deletes == {"b": (True, 200), "never-existed": (True, 200)}, "delete is idempotent"

        eventually(lambda: _assert_equal(client.get_document_count(), 1))
        assert status_of(lambda: client.get_document("b")) == 404
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


# https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("207 Multi-Status ... Some documents
# succeeded, but at least one failed")
# https://learn.microsoft.com/azure/search/search-how-to-load-search-index (per-document 404: "The document couldn't
# be merged because the given key doesn't exist in the index")
def test_partial_batch_failure_returns_multi_status(service, index_client):
    index_name = _support.name("partial")
    index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="name", type=SearchFieldDataType.STRING, searchable=True),
    ]))
    try:
        client = _support.search_client(service, index_name)
        batch = IndexDocumentsBatch()
        batch.add_upload_actions([{"id": "ok", "name": "fine"}])
        batch.add_merge_actions([{"id": "missing", "name": "nothing to merge into"}])
        statuses = []
        results = client.index_documents(
            batch, raw_response_hook=lambda response: statuses.append(response.http_response.status_code))

        assert statuses == [207]
        by_key = {r.key: r for r in results}
        assert (by_key["ok"].succeeded, by_key["ok"].status_code) == (True, 201)
        assert (by_key["missing"].succeeded, by_key["missing"].status_code) == (False, 404)
        assert by_key["missing"].error_message, "a failed action explains why"
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


# https://learn.microsoft.com/rest/api/searchservice/documents/get ($select: "Any field not retrieved will be missing
# from the returned document")
# https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("404 Not Found")
def test_get_document_honours_select(hotels):
    full = _support.fields(hotels.get_document("3"))
    assert full["name"] == "City Center Hotel"
    assert full["tags"] == ["pool", "gym"]
    assert full["rating"] == 4

    selected = _support.fields(hotels.get_document("3", selected_fields=["id", "name"]))
    assert set(selected) == {"id", "name"}
    assert status_of(lambda: hotels.get_document("no-such-hotel")) == 404


# https://learn.microsoft.com/rest/api/searchservice/documents/count
# https://learn.microsoft.com/azure/search/search-pagination-page-layout ("Count is accurate when the index is stable")
def test_document_count_and_total_count(hotels):
    assert hotels.get_document_count() == 6
    results = hotels.search(search_text="*", include_total_count=True)
    assert results.get_count() == 6


# https://learn.microsoft.com/rest/api/searchservice/documents/search-post (SearchMode any/all)
def test_search_mode_any_and_all(hotels):
    assert set(search_keys(hotels, "pool view", search_mode="any", search_fields=["description"])) == {"1", "3", "6"}
    assert search_keys(hotels, "pool view", search_mode="all", search_fields=["description"]) == ["1"]


# https://learn.microsoft.com/rest/api/searchservice/documents/search-post (searchFields)
# https://learn.microsoft.com/azure/search/query-simple-syntax (phrase search, prefix queries)
def test_search_fields_phrases_and_prefixes(hotels):
    assert set(search_keys(hotels, "airport")) == {"2", "5"}
    assert search_keys(hotels, "airport", search_fields=["name"]) == ["5"]
    assert search_keys(hotels, '"ocean view"') == ["1"]
    assert set(search_keys(hotels, "air*", search_fields=["description"])) == {"2", "5"}


# https://learn.microsoft.com/azure/search/search-query-odata-comparison-operators
# https://learn.microsoft.com/azure/search/search-query-odata-logical-operators
@pytest.mark.parametrize("odata_filter, expected", [
    ("rating ge 4", {"1", "3", "6"}),
    ("price lt 100", {"2", "5"}),
    ("category eq 'Resort'", {"1", "4"}),
    ("rating ne 3", {"1", "2", "3", "6"}),
    ("rating ge 4 and parking eq true", {"1"}),
    ("category eq 'Budget' or category eq 'Boutique'", {"2", "5", "6"}),
    ("not (category eq 'Resort')", {"2", "3", "5", "6"}),
    ("parking", {"1", "2", "4"}),
])
def test_filters_with_comparison_and_logical_operators(hotels, odata_filter, expected):
    assert set(search_keys(hotels, "*", filter=odata_filter)) == expected


# https://learn.microsoft.com/azure/search/search-query-odata-search-in-function
# https://learn.microsoft.com/azure/search/search-query-odata-collection-operators
# https://learn.microsoft.com/azure/search/search-query-odata-geo-spatial-functions (distance in kilometers)
@pytest.mark.parametrize("odata_filter, expected", [
    ("search.in(category, 'Budget,Boutique')", {"2", "5", "6"}),
    ("search.in(name, 'Budget Inn|Harbor Boutique', '|')", {"2", "6"}),
    ("tags/any(t: t eq 'pool')", {"1", "3"}),
    ("tags/all(t: t ne 'wifi')", {"3", "4", "6"}),
    ("tags/any(t: search.in(t, 'wifi,shuttle'))", {"1", "2", "5"}),
    (f"geo.distance(location, {SEATTLE}) le 10", {"1", "3", "6"}),
])
def test_filters_with_functions_and_collections(hotels, odata_filter, expected):
    assert set(search_keys(hotels, "*", filter=odata_filter)) == expected


# https://learn.microsoft.com/azure/search/search-query-odata-orderby (multiple sort criteria, search.score(),
# geo.distance)
# https://learn.microsoft.com/azure/search/index-similarity-and-scoring ("A @search.score = 1 indicates an un-scored
# or un-ranked result set ... an empty search (search=*)")
def test_order_by_fields_distance_and_search_score(hotels):
    assert search_keys(hotels, "*", order_by=["price asc"]) == ["2", "5", "4", "6", "3", "1"]
    assert search_keys(hotels, "*", order_by=["search.score() desc", "rating desc", "id asc"]) \
        == ["1", "3", "6", "4", "5", "2"]
    assert search_keys(hotels, "*", order_by=[f"geo.distance(location, {SEATTLE}) asc"]) \
        == ["1", "3", "6", "2", "5", "4"]
    assert all(result["@search.score"] == 1.0 for result in hotels.search(search_text="*"))


# https://learn.microsoft.com/rest/api/searchservice/documents/search-post (select, top, skip, count)
def test_select_top_skip_and_count(hotels):
    results = hotels.search(search_text="*", order_by=["id asc"], select=["id", "name"], top=2, skip=1,
                            include_total_count=True)
    documents = [_support.fields(result) for result in results]
    assert results.get_count() == 6
    assert [d["id"] for d in documents] == ["2", "3"]
    assert all(set(d) == {"id", "name"} for d in documents)


# https://learn.microsoft.com/azure/search/search-pagination-page-layout ("The default page size is 50 ... If the
# number of matches exceed the page size, the response includes information to retrieve the next page")
def test_paging_beyond_fifty_results(service, index_client):
    index_name = _support.name("paging")
    index_client.create_index(SearchIndex(name=index_name, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True, sortable=True),
        SearchField(name="n", type=SearchFieldDataType.INT32, sortable=True, filterable=True),
    ]))
    try:
        client = _support.search_client(service, index_name)
        client.upload_documents([{"id": f"doc-{i:03d}", "n": i} for i in range(60)])
        eventually(lambda: _assert_equal(client.get_document_count(), 60))

        pages = client.search(search_text="*", include_total_count=True).by_page()
        first = list(next(pages))
        assert len(first) == 50, "the default page size is 50"
        second = list(next(pages))
        assert len(second) == 10

        all_keys = search_keys(client, "*")
        assert len(all_keys) == 60
        assert len(set(all_keys)) == 60, "pages do not repeat documents"
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


# https://learn.microsoft.com/azure/search/search-filters ("Filters apply to ... fields that are attributed as
# filterable")
# https://learn.microsoft.com/azure/search/search-query-odata-orderby (sort criteria are sortable fields)
# https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("400 Bad Request")
def test_filtering_or_sorting_on_a_field_without_the_attribute_is_a_bad_request(hotels):
    assert status_of(lambda: search_keys(hotels, "*", filter="description eq 'x'")) == 400
    assert status_of(lambda: search_keys(hotels, "*", order_by=["description asc"])) == 400


# https://learn.microsoft.com/azure/search/search-security-api-keys (query keys: "Read-only access, scoped to the
# documents collection of a search index"; index definitions require an admin key)
# https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("403 Forbidden: Returned when authorization
# fails")
def test_query_key_can_search_but_cannot_write(service, hotels_index, hotels):
    read_only = _support.search_client(service, hotels_index, _support.QUERY_KEY)
    assert set(search_keys(read_only, "pool")) == {"1", "3"}
    assert read_only.get_document("2")["name"] == "Budget Inn"

    assert status_of(lambda: read_only.upload_documents([{"id": "99", "name": "Not allowed"}])) == 403
    assert status_of(lambda: _support.index_client(service, _support.QUERY_KEY).get_index(hotels_index)) == 403
    assert status_of(lambda: hotels.get_document("99")) == 404, "the rejected upload wrote nothing"


def _assert_equal(actual, expected):
    assert actual == expected
