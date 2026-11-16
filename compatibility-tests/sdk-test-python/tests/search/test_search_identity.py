"""Azure AI Search identity through the Python SDKs.

A Microsoft Entra token issued by floci-az's own Entra (the client credentials grant, for the
``https://search.azure.com/.default`` scope the SDK requests), a service's ``authOptions`` and ``disableLocalAuth``
set through ``azure-mgmt-search``, and document-level permission filters queried through ``azure-search-documents``.

The emulator runs at compat parity here, where tokens are not validated and role assignments not evaluated (the
emulator's own tests cover ``parity-overrides.strict-tokens`` and ``strict-rbac``); a service's authentication
settings and an index's permission filters apply at every parity level.

Everything goes over the emulator's plain HTTP. MSAL refuses non-https endpoints, so the token comes from the token
endpoint directly; the SDK's bearer token policy refuses non-https URLs, so the token clients are given the https form
of the endpoint and the management tests' transport, which sends their requests over http. The SDK's api-version
(2026-04-01) has no ``permissionFilter`` in its index model, so the permission-filtered index is created through the
REST API (2026-10-01); its documents are loaded and queried through the SDK, with the two request headers the REST API
defines for document-level access.
"""
import base64
import json
import re
import time
import uuid

import pytest
import requests
from azure.core.credentials import AccessToken, TokenCredential
from azure.core.exceptions import HttpResponseError
from azure.mgmt.search import SearchManagementClient
from azure.mgmt.search.models import (
    DataPlaneAadOrApiKeyAuthOption,
    DataPlaneAuthOptions,
    SearchService,
    Sku,
)
from azure.search.documents import SearchClient
from azure.search.documents.indexes import SearchIndexClient
from azure.search.documents.indexes.models import SearchField, SearchFieldDataType, SearchIndex

from . import _support
from ._support import status_of
from .test_search_management import ArmForceHttpTransport, FakeCredential, SUB

pytestmark = pytest.mark.search_identity

TENANT = "00000000-0000-0000-0000-000000000002"
CLIENT_ID = "11111111-1111-1111-1111-111111111111"
CLIENT_SECRET = "floci-az-dev-secret"
SEARCH_SCOPE = "https://search.azure.com/.default"
RG = f"sdk-test-rg-search-identity-{uuid.uuid4().hex[:8]}"
HTTPS_BASE = re.sub(r"^http://", "https://", _support.EMULATOR_BASE)
READERS = "cccccccc-0000-0000-0000-0000000000c1"


@pytest.fixture(scope="module")
def mgmt():
    client = SearchManagementClient(FakeCredential(), SUB, base_url=HTTPS_BASE, transport=ArmForceHttpTransport())
    created = []
    yield client, created
    for name in created:
        try:
            client.services.delete(RG, name)
        except HttpResponseError:
            pass  # cleanup only: the service may already be gone


class EntraTokenCredential(TokenCredential):
    """A token floci-az's Entra issues to the dev application for the search scope; it checks the scope asked for."""

    def __init__(self):
        response = requests.post(f"{_support.EMULATOR_BASE}/{TENANT}/oauth2/v2.0/token", data={
            "grant_type": "client_credentials", "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
            "scope": SEARCH_SCOPE}, timeout=30)
        response.raise_for_status()
        body = response.json()
        self._token = AccessToken(body["access_token"], int(time.time()) + int(body["expires_in"]))

    def get_token(self, *scopes, **kwargs):
        assert scopes == (SEARCH_SCOPE,), f"the SDK asked for {scopes}"
        return self._token


@pytest.fixture(scope="module")
def credential():
    return EntraTokenCredential()


def _create_service(mgmt, prefix, auth_options=None, disable_local_auth=None):
    client, created = mgmt
    name = _support.service_name(prefix)
    client.services.begin_create_or_update(RG, name, SearchService(
        location="eastus", sku=Sku(name="basic"), auth_options=auth_options,
        disable_local_auth=disable_local_auth), polling_interval=1).result()
    created.append(name)
    return name, client.admin_keys.get(RG, name).primary_key


def _aad_or_api_key(mode):
    return DataPlaneAuthOptions(aad_or_api_key=DataPlaneAadOrApiKeyAuthOption(aad_auth_failure_mode=mode))


def _token_index_client(service, credential):
    return SearchIndexClient(f"{HTTPS_BASE}/{service}-search", credential, transport=ArmForceHttpTransport())


def _token_search_client(service, index, credential):
    return SearchClient(f"{HTTPS_BASE}/{service}-search", index, credential, transport=ArmForceHttpTransport())


# https://learn.microsoft.com/azure/search/search-security-enable-roles ("Both: Requests are valid using either an API
# key or role-based access control"; tokens for the scope https://search.azure.com/.default)
def test_an_entra_token_authorizes_a_service_that_accepts_roles(mgmt, credential):
    service, key = _create_service(mgmt, "pyidboth", _aad_or_api_key("http401WithBearerChallenge"))
    index = _support.name("hotels")
    _token_index_client(service, credential).create_index(SearchIndex(name=index, fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="name", type=SearchFieldDataType.STRING, searchable=True),
        SearchField(name="rating", type=SearchFieldDataType.INT32, filterable=True),
    ]))
    hotels = _token_search_client(service, index, credential)
    hotels.upload_documents([
        {"id": "1", "name": "Ocean View Resort", "rating": 5},
        {"id": "2", "name": "Ocean Budget Inn", "rating": 2},
        {"id": "3", "name": "Mountain Lodge", "rating": 3},
    ])
    _support.eventually(lambda: _assert_count(hotels, 3))
    assert _support.search_keys(hotels, "ocean", filter="rating eq 5") == ["1"]
    assert _support.search_client(service, index, key).get_document_count() == 3


# https://learn.microsoft.com/azure/search/search-security-rbac ("If you don't change this setting to Both or
# Role-based access control, all requests that use role-based authentication are automatically denied")
def test_a_service_that_accepts_only_keys_refuses_tokens(mgmt, credential):
    service, key = _create_service(mgmt, "pyidkeys")
    assert status_of(lambda: list(_token_index_client(service, credential).list_index_names())) == 403
    assert list(_support.index_client(service, key).list_index_names()) == []


# https://learn.microsoft.com/azure/search/search-security-enable-roles ("Disabling API keys causes the search service
# to refuse all data-related requests that pass an API key in the header")
def test_disabling_local_auth_refuses_keys_and_keeps_tokens(mgmt, credential):
    service, key = _create_service(mgmt, "pyidroles", disable_local_auth=True)
    assert status_of(lambda: list(_support.index_client(service, key).list_index_names())) == 403
    assert list(_token_index_client(service, credential).list_index_names()) == []


# https://learn.microsoft.com/rest/api/searchmanagement/services/create-or-update#aadauthfailuremode
# (http401WithBearerChallenge: "401 (Unauthorized) and present a Bearer Challenge"; http403: "403 (Forbidden)")
def test_the_failure_mode_answers_a_request_without_credentials(mgmt):
    challenge, _ = _create_service(mgmt, "pyid401", _aad_or_api_key("http401WithBearerChallenge"))
    response = requests.get(f"{_support.EMULATOR_BASE}/{challenge}-search/indexes?api-version=2026-04-01",
                            timeout=30)
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"].startswith("Bearer ")
    assert 'resource="https://search.azure.com"' in response.headers["WWW-Authenticate"]

    forbidden, _ = _create_service(mgmt, "pyid403", _aad_or_api_key("http403"))
    assert requests.get(f"{_support.EMULATOR_BASE}/{forbidden}-search/indexes?api-version=2026-04-01",
                        timeout=30).status_code == 403


# https://learn.microsoft.com/azure/search/search-index-access-control-lists-and-rbac-push-api (userIds, groupIds,
# "all" and "none") and https://learn.microsoft.com/azure/search/search-query-access-control-rbac-enforcement ("If the
# query token is omitted, only public documents accessible to everyone are returned"; elevated read)
def test_document_level_permissions_trim_results_to_the_end_user(credential):
    service = _support.service_name("pyidacl")
    index = _support.name("acl")
    created = requests.post(
        f"{_support.EMULATOR_BASE}/{service}-search/indexes?api-version=2026-10-01",
        headers={"api-key": _support.ADMIN_KEY},
        json={"name": index, "permissionFilterOption": "enabled", "fields": [
            {"name": "id", "type": "Edm.String", "key": True},
            {"name": "title", "type": "Edm.String"},
            {"name": "oid", "type": "Collection(Edm.String)", "filterable": True, "permissionFilter": "userIds"},
            {"name": "group", "type": "Collection(Edm.String)", "filterable": True, "permissionFilter": "groupIds"},
        ]}, timeout=30)
    assert created.status_code == 201, created.text

    token = credential.get_token(SEARCH_SCOPE).token
    user = _object_id(token)
    admin = _support.search_client(service, index)
    admin.upload_documents([
        {"id": "1", "title": "mine", "oid": [user], "group": ["none"]},
        {"id": "2", "title": "public", "oid": ["all"], "group": []},
        {"id": "3", "title": "readers", "oid": ["none"], "group": [READERS]},
        {"id": "4", "title": "nobody", "oid": ["none"], "group": ["none"]},
    ])
    as_user = {"x-ms-query-source-authorization": token}
    _support.eventually(lambda: _assert_keys(admin, {"1", "2"}, headers=as_user))
    _assert_keys(admin, {"2"})
    _assert_keys(admin, {"1", "2", "3", "4"}, headers={"x-ms-enable-elevated-read": "true"})
    assert admin.get_document_count(headers=as_user) == 2
    assert admin.get_document("1", headers=as_user)["title"] == "mine"
    assert status_of(lambda: admin.get_document("4", headers=as_user)) == 404
    query = _support.search_client(service, index, _support.QUERY_KEY)
    assert status_of(lambda: list(query.search(search_text="*",
                                               headers={"x-ms-enable-elevated-read": "true"}))) == 403


def _assert_count(client, expected):
    assert client.get_document_count() == expected


def _assert_keys(client, expected, **kwargs):
    assert set(_support.search_keys(client, **kwargs)) == expected


def _object_id(token):
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["oid"]
