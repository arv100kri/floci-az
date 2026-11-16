"""Azure AI Search network isolation on the management plane through ``azure-mgmt-search``.

The service's private link resource, its shared private links (long-running create and delete), its network security
perimeter configurations, and the private endpoint connections a ``Microsoft.Network`` private endpoint makes to it.
The private endpoint is created over the raw ARM wire protocol, as the Network suites create theirs.
"""
import os
import re
import uuid

import pytest
import requests
from azure.core.exceptions import HttpResponseError
from azure.mgmt.search import SearchManagementClient
from azure.mgmt.search.models import (
    PrivateEndpointConnection,
    PrivateEndpointConnectionProperties,
    PrivateEndpointConnectionPropertiesPrivateLinkServiceConnectionState,
    SearchService,
    SharedPrivateLinkResource,
    SharedPrivateLinkResourceProperties,
    Sku,
)

from . import _support
from ._support import status_of
from .test_search_management import ArmForceHttpTransport, FakeCredential

pytestmark = pytest.mark.search_arm

SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
SUFFIX = uuid.uuid4().hex[:8]
RG = f"sdk-test-rg-search-net-{SUFFIX}"
# Letters and digits only, so the data-plane path /{service}-search stays unambiguous.
SERVICE = f"pynet{SUFFIX}"
SERVICE_ID = f"/subscriptions/{SUB}/resourceGroups/{RG}/providers/Microsoft.Search/searchServices/{SERVICE}"
STORAGE_ID = f"/subscriptions/{SUB}/resourceGroups/{RG}/providers/Microsoft.Storage/storageAccounts/pynet{SUFFIX}"
NETWORK = f"{_support.EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}/providers/Microsoft.Network"
NETWORK_API = {"api-version": "2024-05-01"}


@pytest.fixture(scope="module")
def mgmt():
    base_url = re.sub(r"^http://", "https://", _support.EMULATOR_BASE)
    return SearchManagementClient(FakeCredential(), SUB, base_url=base_url, transport=ArmForceHttpTransport())


@pytest.fixture(scope="module")
def service(mgmt):
    mgmt.services.begin_create_or_update(
        RG, SERVICE, SearchService(location="eastus", sku=Sku(name="basic")), polling_interval=1).result()
    yield SERVICE
    try:
        mgmt.services.delete(RG, SERVICE)
    except HttpResponseError:
        pass  # cleanup only


def _value(enum_or_str):
    return getattr(enum_or_str, "value", enum_or_str)


def _link(group_id):
    return SharedPrivateLinkResource(properties=SharedPrivateLinkResourceProperties(
        private_link_resource_id=STORAGE_ID, group_id=group_id, request_message="please approve"))


# https://learn.microsoft.com/rest/api/searchmanagement/private-link-resources/list-supported (one resource, groupId
# searchService, required zone privatelink.search.windows.net)
def test_the_services_private_link_resource_is_search_service(mgmt, service):
    resources = list(mgmt.private_link_resources.list_supported(RG, service))
    assert len(resources) == 1
    assert resources[0].properties.group_id == "searchService"
    assert resources[0].properties.required_members == ["searchService"]
    assert resources[0].properties.required_zone_names == ["privatelink.search.windows.net"]
    assert "blob" in [t.properties.group_id for t in resources[0].properties.shareable_private_link_resource_types]


# https://learn.microsoft.com/rest/api/searchmanagement/shared-private-link-resources/create-or-update (202 Accepted,
# long-running) and .../delete (202 Accepted, long-running)
# https://learn.microsoft.com/azure/search/search-indexer-howto-access-private ("there can be only one shared private
# link created on your service for each resource and subresource combination")
def test_a_shared_private_link_goes_through_its_long_running_operations(mgmt, service):
    created = mgmt.shared_private_link_resources.begin_create_or_update(
        RG, service, "blob-link", _link("blob"), polling_interval=1).result()
    assert created.name == "blob-link"
    assert _value(created.properties.status) == "Approved"
    assert _value(created.properties.provisioning_state) == "Succeeded"

    fetched = mgmt.shared_private_link_resources.get(RG, service, "blob-link")
    assert (fetched.properties.private_link_resource_id, fetched.properties.group_id) == (STORAGE_ID, "blob")
    assert [link.name for link in mgmt.shared_private_link_resources.list_by_service(RG, service)] == ["blob-link"]
    assert status_of(lambda: mgmt.shared_private_link_resources.begin_create_or_update(
        RG, service, "blob-again", _link("blob"), polling_interval=1).result()) == 409

    mgmt.shared_private_link_resources.begin_delete(RG, service, "blob-link", polling_interval=1).result()
    assert status_of(lambda: mgmt.shared_private_link_resources.get(RG, service, "blob-link")) == 404
    assert list(mgmt.shared_private_link_resources.list_by_service(RG, service)) == []


# https://learn.microsoft.com/rest/api/searchmanagement/private-endpoint-connections/update (the resource owner approves
# or rejects) and .../delete (200 with the connection, Disconnected)
# https://learn.microsoft.com/azure/private-link/private-endpoint-overview ("Pending: The connection was created
# manually and is pending approval by the private-link resource owner")
def test_a_manual_private_endpoint_connection_is_approved_and_removed_on_the_service(mgmt, service):
    subnet = f"/subscriptions/{SUB}/resourceGroups/{RG}/providers/Microsoft.Network/virtualNetworks/vnet/subnets/pe"
    requests.put(f"{NETWORK}/virtualNetworks/vnet", params=NETWORK_API, json={
        "location": "eastus", "properties": {"addressSpace": {"addressPrefixes": ["10.0.0.0/16"]},
                                             "subnets": [{"name": "pe", "properties": {"addressPrefix": "10.0.1.0/24"}}]},
    }).raise_for_status()
    endpoint = f"{NETWORK}/privateEndpoints/pe-{SUFFIX}"
    requests.put(endpoint, params=NETWORK_API, json={
        "location": "eastus", "properties": {"subnet": {"id": subnet}, "manualPrivateLinkServiceConnections": [
            {"name": "to-search", "properties": {"privateLinkServiceId": SERVICE_ID, "groupIds": ["searchService"],
                                                 "requestMessage": "please approve"}}]},
    }).raise_for_status()

    connections = list(mgmt.private_endpoint_connections.list_by_service(RG, service))
    assert len(connections) == 1
    name = connections[0].name
    assert name.startswith(f"pe-{SUFFIX}.")
    assert _value(connections[0].properties.private_link_service_connection_state.status) == "Pending"

    approved = mgmt.private_endpoint_connections.update(RG, service, name, PrivateEndpointConnection(
        properties=PrivateEndpointConnectionProperties(
            private_link_service_connection_state=PrivateEndpointConnectionPropertiesPrivateLinkServiceConnectionState(
                status="Approved", description="approved by the search admin"))))
    assert _value(approved.properties.private_link_service_connection_state.status) == "Approved"
    state = requests.get(endpoint, params=NETWORK_API).json()["properties"]["manualPrivateLinkServiceConnections"][0][
        "properties"]["privateLinkServiceConnectionState"]
    assert (state["status"], state["description"]) == ("Approved", "approved by the search admin")

    deleted = mgmt.private_endpoint_connections.delete(RG, service, name)
    assert _value(deleted.properties.private_link_service_connection_state.status) == "Disconnected"
    assert list(mgmt.private_endpoint_connections.list_by_service(RG, service)) == []
    assert status_of(lambda: mgmt.private_endpoint_connections.get(RG, service, name)) == 404


# https://learn.microsoft.com/rest/api/searchmanagement/network-security-perimeter-configurations/list-by-service
def test_a_service_is_in_no_network_security_perimeter(mgmt, service):
    assert list(mgmt.network_security_perimeter_configurations.list_by_service(RG, service)) == []
    assert status_of(lambda: mgmt.network_security_perimeter_configurations.get(
        RG, service, "00000001-2222-3333-4444-111144444444.assoc1")) == 404
