"""Clients, names and fixtures shared by the Azure AI Search compatibility tests.

The data plane is addressed path style, ``{FLOCI_AZ_ENDPOINT}/{service}-search``; a data-plane request
provisions the named service with floci-az's development keys (``SearchServiceRegistry``). The Python
SDK's ``AzureKeyCredentialPolicy`` does not insist on HTTPS, so the clients talk to the emulator's plain
HTTP endpoint directly.
"""
import os
import time
import uuid

from azure.core.credentials import AzureKeyCredential
from azure.core.exceptions import HttpResponseError
from azure.search.documents import SearchClient
from azure.search.documents.indexes import SearchIndexClient, SearchIndexerClient
from azure.search.documents.indexes.models import (
    SearchField,
    SearchFieldDataType,
    SearchIndex,
)

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")

ADMIN_KEY = "FlociAzSearchDevPrimaryAdminKey0000000000000000000000"
SECONDARY_ADMIN_KEY = "FlociAzSearchDevSecondaryAdminKey00000000000000000000"
QUERY_KEY = "FlociAzSearchDevQueryKey000000000000000000000000000000"

# Upper bound for writes to become visible: documents and alias updates are near real time on Azure.
VISIBILITY_TIMEOUT = 15

# Reference point for the geo tests: downtown Seattle, longitude first as in OData POINT(lon lat).
SEATTLE = "geography'POINT(-122.335 47.608)'"

COLLECTION_OF_STRINGS = f"Collection({SearchFieldDataType.STRING})"
COLLECTION_OF_SINGLES = f"Collection({SearchFieldDataType.SINGLE})"


def service_name(prefix):
    """Lowercase letters and digits only, so the ``-search`` path suffix stays unambiguous."""
    return f"{prefix}{uuid.uuid4().hex[:10]}"


def name(prefix):
    """An index, alias, synonym map, data source, indexer or skillset name."""
    return f"{prefix}-{uuid.uuid4().hex[:10]}"


def endpoint(service):
    return f"{EMULATOR_BASE}/{service}-search"


def index_client(service, key=ADMIN_KEY):
    return SearchIndexClient(endpoint(service), AzureKeyCredential(key))


def indexer_client(service, key=ADMIN_KEY):
    return SearchIndexerClient(endpoint(service), AzureKeyCredential(key))


def search_client(service, index_or_alias, key=ADMIN_KEY):
    return SearchClient(endpoint(service), index_or_alias, AzureKeyCredential(key))


def keys(results, field="id"):
    return [result[field] for result in results]


def search_keys(client, search_text="*", **kwargs):
    return keys(client.search(search_text=search_text, **kwargs))


def fields(document):
    """The document's own fields, without the ``@search.*`` annotations."""
    return {k: v for k, v in dict(document).items() if not k.startswith("@")}


def status_of(call):
    """The HTTP status of the error ``call`` raises."""
    try:
        call()
    except HttpResponseError as error:
        return error.status_code
    raise AssertionError("expected the call to fail")


def eventually(assertion, timeout=VISIBILITY_TIMEOUT):
    """Re-runs ``assertion`` until it passes or ``timeout`` seconds elapse, then re-raises its failure."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            assertion()
            return
        except AssertionError:
            if time.monotonic() > deadline:
                raise
        time.sleep(0.25)


def poll(supplier, done, timeout):
    """Polls ``supplier`` until ``done`` accepts its value; returns the last value either way."""
    deadline = time.monotonic() + timeout
    value = supplier()
    while not done(value) and time.monotonic() < deadline:
        time.sleep(0.5)
        value = supplier()
    return value


def delete_quietly(delete, resource_name):
    try:
        delete(resource_name)
    except HttpResponseError:
        pass  # cleanup only: the resource may already be gone, or the test failed before creating it


def hotels_index(index_name, suggesters=None):
    return SearchIndex(
        name=index_name,
        fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True, filterable=True,
                        sortable=True, searchable=False),
            SearchField(name="name", type=SearchFieldDataType.STRING, searchable=True, filterable=True,
                        sortable=True, facetable=False),
            SearchField(name="description", type=SearchFieldDataType.STRING, searchable=True,
                        filterable=False, sortable=False, facetable=False),
            SearchField(name="category", type=SearchFieldDataType.STRING, searchable=False,
                        filterable=True, sortable=True, facetable=True),
            SearchField(name="tags", type=COLLECTION_OF_STRINGS, searchable=True, filterable=True,
                        facetable=True),
            SearchField(name="rating", type=SearchFieldDataType.INT32, filterable=True, sortable=True,
                        facetable=True),
            SearchField(name="price", type=SearchFieldDataType.DOUBLE, filterable=True, sortable=True,
                        facetable=True),
            SearchField(name="parking", type=SearchFieldDataType.BOOLEAN, filterable=True, facetable=True),
            SearchField(name="location", type=SearchFieldDataType.GEOGRAPHY_POINT, filterable=True,
                        sortable=True),
        ],
        suggesters=suggesters,
    )


def _hotel(hotel_id, hotel_name, description, category, tags, rating, price, parking, lon, lat):
    return {
        "id": hotel_id,
        "name": hotel_name,
        "description": description,
        "category": category,
        "tags": tags,
        "rating": rating,
        "price": price,
        "parking": parking,
        "location": {"type": "Point", "coordinates": [lon, lat]},
    }


# Six hotels whose filters, sorts and distances have one right answer (the Java suite uses the same corpus).
HOTELS = [
    _hotel("1", "Ocean View Resort", "Luxury resort with a pool and an ocean view", "Resort",
           ["pool", "view", "wifi"], 5, 250.0, True, -122.33, 47.61),
    _hotel("2", "Budget Inn", "Cheap motel near the airport with free parking", "Budget",
           ["parking", "wifi"], 2, 60.0, True, -122.30, 47.45),
    _hotel("3", "City Center Hotel", "Modern hotel in the city center with a rooftop pool", "Luxury",
           ["pool", "gym"], 4, 180.0, False, -122.34, 47.60),
    _hotel("4", "Mountain Lodge", "Rustic lodge with hiking trails and a stone fireplace", "Resort",
           ["hiking", "fireplace"], 3, 120.0, True, -105.27, 40.01),
    _hotel("5", "Airport Suites", "Convenient suites next to the airport terminal", "Budget",
           ["wifi", "shuttle"], 3, 90.0, False, -122.31, 47.44),
    _hotel("6", "Harbor Boutique", "Small boutique hotel on the harbor with a view", "Boutique",
           ["boutique", "view"], 4, 150.0, False, -122.40, 47.65),
]
