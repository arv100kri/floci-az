#!/usr/bin/env bats
# Azure AI Search: the Microsoft.Search/searchServices management plane through `az search`.
# https://learn.microsoft.com/cli/azure/search/service, /search/admin-key and /search/query-key.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az search service create -n "$SEARCH_NAME" -g "$RG_NAME" -l "$LOCATION" --sku basic -o none
}

setup() {
    load 'test_helper/common-setup'
}

# https://learn.microsoft.com/rest/api/searchmanagement/services/create-or-update (replicaCount defaults to 1;
# provisioningState ends "succeeded")
@test "az search service create + show: basic sku, one replica, succeeded" {
    run az_json search service show -n "$SEARCH_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$SEARCH_NAME"
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "basic"
    # The CLI flattens properties onto the resource; accept either shape.
    assert_equal "$(echo "$output" | jq -r '(.replicaCount // .properties.replicaCount)')" "1"
    assert_equal "$(echo "$output" | jq -r '(.provisioningState // .properties.provisioningState) | ascii_downcase')" "succeeded"
}

# https://learn.microsoft.com/rest/api/searchmanagement/admin-keys/get ("Two admin keys, primary and secondary, are
# generated when the service is created": https://learn.microsoft.com/azure/search/search-security-api-keys)
@test "az search admin-key show: distinct primary and secondary admin keys" {
    run az_json search admin-key show -g "$RG_NAME" --service-name "$SEARCH_NAME"
    assert_success
    local primary secondary
    primary="$(echo "$output" | jq -r '.primaryKey')"
    secondary="$(echo "$output" | jq -r '.secondaryKey')"
    [ -n "$primary" ] && [ "$primary" != "null" ]
    [ -n "$secondary" ] && [ "$secondary" != "null" ]
    [ "$primary" != "$secondary" ]
}

# https://learn.microsoft.com/rest/api/searchmanagement/query-keys/create and /query-keys/list-by-search-service
# ("One query key is generated with the service. More can be created on demand":
# https://learn.microsoft.com/azure/search/search-security-api-keys)
@test "az search query-key create + list: the new key is listed beside the default one" {
    run az_json search query-key create -n "$SEARCH_QUERY_KEY_NAME" -g "$RG_NAME" --service-name "$SEARCH_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$SEARCH_QUERY_KEY_NAME"
    local key
    key="$(echo "$output" | jq -r '.key')"
    [ -n "$key" ] && [ "$key" != "null" ]

    run az_json search query-key list -g "$RG_NAME" --service-name "$SEARCH_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg k "$key" '[.[] | select(.key == $k)] | length')" "1"
    [ "$(echo "$output" | jq 'length')" -ge 2 ]
}

# https://learn.microsoft.com/rest/api/searchmanagement/services/delete (200 OK or 204 No Content)
@test "az search service delete: the service is gone afterwards" {
    run az search service delete -n "$SEARCH_NAME" -g "$RG_NAME" --yes -o none
    assert_success

    run az search service show -n "$SEARCH_NAME" -g "$RG_NAME" -o none
    assert_failure
}
