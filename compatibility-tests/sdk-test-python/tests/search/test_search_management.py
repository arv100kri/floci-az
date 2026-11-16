"""Azure AI Search management plane (``Microsoft.Search/searchServices``) through ``azure-mgmt-search``.

Create, read, list, update and delete a search service, its admin and query keys, and name availability;
one test checks that the keys the management plane issues are the ones the service's data plane accepts.

The management client requires an https base URL for its Bearer token policy, so the transport sends the
requests to the emulator's own scheme and restores https on the long-running-operation headers it
follows, the way the Key Vault sub-suite's ForceHttpTransport does for its challenge.
"""
import os
import re
import time
import uuid

import pytest
from azure.core.credentials import AccessToken, TokenCredential
from azure.core.exceptions import HttpResponseError
from azure.core.pipeline.transport import RequestsTransport
from azure.mgmt.search import SearchManagementClient
from azure.mgmt.search.models import SearchService, SearchServiceUpdate, Sku

from . import _support
from ._support import status_of

pytestmark = pytest.mark.search_arm

SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
SUFFIX = uuid.uuid4().hex[:8]
RG = f"sdk-test-rg-search-{SUFFIX}"
# Letters and digits only, so the data-plane path /{service}-search stays unambiguous.
SERVICE = f"pyarm{SUFFIX}"


class FakeCredential(TokenCredential):
    """Returns a static fake Bearer token: no Entra ID needed for the local emulator."""

    def get_token(self, *scopes, **kwargs):
        return AccessToken("fake-token-for-local-emulator", int(time.time()) + 3600)


class ArmForceHttpTransport(RequestsTransport):
    """Sends https:// requests to the emulator's http:// endpoint and keeps polling URLs on https://."""

    LRO_HEADERS = ("Azure-AsyncOperation", "Location", "Operation-Location")

    def send(self, request, **kwargs):
        original_url = request.url
        request.url = original_url.replace("https://", "http://", 1)
        try:
            response = super().send(request, **kwargs)
        finally:
            request.url = original_url
        for header in self.LRO_HEADERS:
            value = response.headers.get(header)
            if value and value.startswith("http://"):
                response.headers[header] = "https://" + value[len("http://"):]
        return response


@pytest.fixture(scope="module")
def mgmt():
    base_url = re.sub(r"^http://", "https://", _support.EMULATOR_BASE)
    return SearchManagementClient(FakeCredential(), SUB, base_url=base_url, transport=ArmForceHttpTransport())


@pytest.fixture(scope="module")
def created_service(mgmt):
    poller = mgmt.services.begin_create_or_update(
        RG, SERVICE, SearchService(location="eastus", sku=Sku(name="basic"), tags={"suite": "python-sdk"}),
        polling_interval=1)
    service = poller.result()
    yield service
    try:
        mgmt.services.delete(RG, SERVICE)
    except HttpResponseError:
        pass  # cleanup only: test_delete_service_removes_it deletes it first


def _value(enum_or_str):
    return getattr(enum_or_str, "value", enum_or_str).lower()


# https://learn.microsoft.com/rest/api/searchmanagement/services/create-or-update (replicaCount and partitionCount
# default to 1, hostingMode to Default, publicNetworkAccess to Enabled; provisioningState ends "Succeeded"; status
# "running": "no provisioning operations are underway")
def test_create_service_reaches_succeeded(created_service):
    assert created_service.name == SERVICE
    assert created_service.type == "Microsoft.Search/searchServices"
    assert created_service.id == (f"/subscriptions/{SUB}/resourceGroups/{RG}"
                                  f"/providers/Microsoft.Search/searchServices/{SERVICE}")
    assert _value(created_service.provisioning_state) == "succeeded"
    assert _value(created_service.status) == "running"
    assert _value(created_service.sku.name) == "basic"
    assert (created_service.replica_count, created_service.partition_count) == (1, 1)
    assert _value(created_service.hosting_mode) == "default"
    assert _value(created_service.public_network_access) == "enabled"
    assert created_service.tags == {"suite": "python-sdk"}


# https://learn.microsoft.com/rest/api/searchmanagement/services/get
# https://learn.microsoft.com/rest/api/searchmanagement/services/list-by-resource-group
# https://learn.microsoft.com/rest/api/searchmanagement/services/list-by-subscription
def test_get_and_list_services(mgmt, created_service):
    fetched = mgmt.services.get(RG, SERVICE)
    assert (fetched.name, fetched.location, _value(fetched.sku.name)) == (SERVICE, "eastus", "basic")
    assert [s.name for s in mgmt.services.list_by_resource_group(RG)] == [SERVICE]
    assert SERVICE in [s.name for s in mgmt.services.list_by_subscription()]


# https://learn.microsoft.com/rest/api/searchmanagement/services/update (PATCH, 200 OK; replicaCount "between 1 and 3
# inclusive for basic SKU")
def test_update_changes_replica_count_and_tags(mgmt, created_service):
    updated = mgmt.services.update(RG, SERVICE, SearchServiceUpdate(
        replica_count=2, tags={"suite": "python-sdk", "stage": "updated"}))
    assert updated.replica_count == 2
    assert updated.tags["stage"] == "updated"
    fetched = mgmt.services.get(RG, SERVICE)
    assert fetched.replica_count == 2
    assert _value(fetched.sku.name) == "basic", "a PATCH leaves the properties it does not name"


# https://learn.microsoft.com/rest/api/searchmanagement/services/check-name-availability (reason Invalid or
# AlreadyExists; names are lowercase letters, digits or dashes, 2 to 60 characters)
def test_check_name_availability(mgmt, created_service):
    taken = mgmt.services.check_name_availability(SERVICE)
    assert taken.is_name_available is False
    assert _value(taken.reason) == "alreadyexists"
    assert mgmt.services.check_name_availability(f"pyfree{SUFFIX}").is_name_available is True
    invalid = mgmt.services.check_name_availability("Invalid_Name")
    assert invalid.is_name_available is False
    assert _value(invalid.reason) == "invalid"


# https://learn.microsoft.com/rest/api/searchmanagement/admin-keys/get
# https://learn.microsoft.com/rest/api/searchmanagement/query-keys/create
# https://learn.microsoft.com/rest/api/searchmanagement/query-keys/list-by-search-service
# https://learn.microsoft.com/rest/api/searchmanagement/query-keys/delete (by key value)
# https://learn.microsoft.com/azure/search/search-security-api-keys ("One query key is generated with the service.
# More can be created on demand")
def test_admin_and_query_keys(mgmt, created_service):
    admin = mgmt.admin_keys.get(RG, SERVICE)
    assert admin.primary_key and admin.secondary_key
    assert admin.primary_key != admin.secondary_key

    assert len(list(mgmt.query_keys.list_by_search_service(RG, SERVICE))) == 1
    created = mgmt.query_keys.create(RG, SERVICE, "app-key")
    assert created.name == "app-key" and created.key
    assert created.key in [k.key for k in mgmt.query_keys.list_by_search_service(RG, SERVICE)]
    mgmt.query_keys.delete(RG, SERVICE, created.key)
    remaining = [k.key for k in mgmt.query_keys.list_by_search_service(RG, SERVICE)]
    assert created.key not in remaining and len(remaining) == 1


# https://learn.microsoft.com/azure/search/search-security-api-keys (admin keys give full data-plane access; query keys
# are read-only on documents; after a regeneration, requests "using those keys will fail with HTTP 403 Forbidden")
# https://learn.microsoft.com/rest/api/searchmanagement/admin-keys/regenerate
def test_management_plane_keys_authorize_the_data_plane(mgmt, created_service):
    admin = mgmt.admin_keys.get(RG, SERVICE)
    assert list(_support.index_client(SERVICE, admin.primary_key).list_index_names()) == []

    query_key = next(iter(mgmt.query_keys.list_by_search_service(RG, SERVICE))).key
    assert status_of(lambda: list(_support.index_client(SERVICE, query_key).list_index_names())) == 403

    regenerated = mgmt.admin_keys.regenerate(RG, SERVICE, "primary")
    assert regenerated.primary_key != admin.primary_key
    assert regenerated.secondary_key == admin.secondary_key, "only the named key is regenerated"
    assert list(_support.index_client(SERVICE, regenerated.primary_key).list_index_names()) == []
    assert status_of(lambda: list(_support.index_client(SERVICE, admin.primary_key).list_index_names())) == 403


# https://learn.microsoft.com/azure/search/search-security-api-keys ("A request made to a search service endpoint is
# accepted if both the request and the API key are valid")
# https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("403 Forbidden: Returned when authorization
# fails")
def test_an_unknown_api_key_is_rejected(created_service):
    unknown = _support.search_client(SERVICE, "hotels", "not-a-key-of-this-service")
    assert status_of(lambda: _support.search_keys(unknown, "*")) == 403
    assert status_of(lambda: list(_support.index_client(SERVICE, "not-a-key-of-this-service")
                                  .list_index_names())) == 403


# https://learn.microsoft.com/rest/api/searchmanagement/services/delete (200 OK or 204 No Content)
def test_delete_service_removes_it(mgmt, created_service):
    mgmt.services.delete(RG, SERVICE)
    assert status_of(lambda: mgmt.services.get(RG, SERVICE)) == 404
    assert SERVICE not in [s.name for s in mgmt.services.list_by_resource_group(RG)]
