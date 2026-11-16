"""Fixtures for the Azure AI Search compatibility tests (see ``_support.py`` for the clients)."""
import pytest
from azure.search.documents.indexes.models import SearchSuggester

from . import _support


@pytest.fixture(scope="module")
def service(request):
    """A search service of the module's own, provisioned by its first data-plane request."""
    module = request.module.__name__.rsplit(".", 1)[-1].replace("test_search_", "").replace("_", "")
    return _support.service_name(("py" + module)[:20])


@pytest.fixture(scope="module")
def index_client(service):
    return _support.index_client(service)


@pytest.fixture(scope="module")
def indexer_client(service):
    return _support.indexer_client(service)


@pytest.fixture(scope="module")
def hotels_index(service, index_client):
    """The six-hotel corpus in an index with a suggester ``sg`` on ``name``; yields the index name."""
    index_name = _support.name("hotels")
    index_client.create_index(_support.hotels_index(
        index_name, suggesters=[SearchSuggester(name="sg", source_fields=["name"])]))
    client = _support.search_client(service, index_name)
    client.upload_documents(_support.HOTELS)
    _support.eventually(lambda: _assert_count(client, len(_support.HOTELS)))
    yield index_name
    _support.delete_quietly(index_client.delete_index, index_name)


@pytest.fixture(scope="module")
def hotels(service, hotels_index):
    """An admin-key SearchClient on the hotels index."""
    return _support.search_client(service, hotels_index)


def _assert_count(client, expected):
    assert client.get_document_count() == expected
