"""Azure AI Search query features through ``azure-search-documents``.

The full Lucene syntax, facets, hit highlighting, suggestions and autocomplete, synonym maps, scoring
profiles, index aliases, the analyze API and custom analyzers.
"""
import pytest
from azure.search.documents.indexes.models import (
    AnalyzeTextOptions,
    CustomAnalyzer,
    MagnitudeScoringFunction,
    MagnitudeScoringParameters,
    ScoringProfile,
    SearchAlias,
    SearchField,
    SearchFieldDataType,
    SearchIndex,
    SynonymMap,
    TextWeights,
)

from . import _support
from ._support import eventually, search_keys, status_of

pytestmark = pytest.mark.search_query


def _lucene(client, query):
    return search_keys(client, query, query_type="full")


# https://learn.microsoft.com/azure/search/query-lucene-syntax (fielded search, Boolean operators, NOT in full syntax)
def test_full_syntax_fielded_and_boolean_queries(hotels):
    assert _lucene(hotels, "name:lodge") == ["4"]
    assert set(_lucene(hotels, "pool AND view")) == {"1"}
    assert set(_lucene(hotels, "pool OR view")) == {"1", "3", "6"}
    assert set(_lucene(hotels, "pool -rooftop")) == {"1"}
    assert set(_lucene(hotels, "pool NOT rooftop")) == {"1"}


# https://learn.microsoft.com/azure/search/query-lucene-syntax (prefix and infix wildcards, suffix through a regular
# expression, "/[mh]otel/", fuzzy "blue~1", proximity "\"hotel airport\"~5")
def test_full_syntax_wildcard_regex_fuzzy_and_proximity(hotels):
    assert set(_lucene(hotels, "mo*n")) == {"3", "4"}
    assert _lucene(hotels, "l?dge") == ["4"]
    assert set(_lucene(hotels, "/.*port/")) == {"2", "5"}
    assert set(_lucene(hotels, "/[mh]otel/")) == {"2", "3", "6"}
    assert _lucene(hotels, "montain~1") == ["4"]
    assert _lucene(hotels, '"hotel harbor"~5') == ["6"]
    assert _lucene(hotels, '"hotel harbor"') == [], "without slop the terms must be adjacent"


# https://learn.microsoft.com/azure/search/search-faceted-navigation (count, sort:value ascending, values buckets
# "one for base rate 0 up to but not including 10, one for 10 up to but not including 20, one for 20 and above",
# interval)
def test_facets_count_values_ranges_and_intervals(hotels):
    results = hotels.search(search_text="*", facets=[
        "category,count:10,sort:value", "tags", "price,values:100|200", "rating,interval:2"])
    facets = results.get_facets()

    assert [(f["value"], f["count"]) for f in facets["category"]] == [
        ("Boutique", 1), ("Budget", 2), ("Luxury", 1), ("Resort", 2)]
    assert {f["value"]: f["count"] for f in facets["tags"]} == {
        "wifi": 3, "pool": 2, "view": 2, "parking": 1, "gym": 1, "hiking": 1, "fireplace": 1,
        "shuttle": 1, "boutique": 1}

    prices = facets["price"]
    assert [p["count"] for p in prices] == [2, 3, 1]
    assert prices[0]["to"] == 100 and "from" not in prices[0]
    assert (prices[1]["from"], prices[1]["to"]) == (100, 200)
    assert prices[2]["from"] == 200 and "to" not in prices[2]

    assert {f["value"]: f["count"] for f in facets["rating"]} == {2: 3, 4: 3}


# https://learn.microsoft.com/azure/search/search-faceted-navigation (facets summarize the matched documents)
def test_facets_reflect_the_filtered_results(hotels):
    facets = hotels.search(search_text="*", filter="parking eq true", facets=["category"]).get_facets()
    assert {f["value"]: f["count"] for f in facets["category"]} == {"Resort": 2, "Budget": 1}


# https://learn.microsoft.com/azure/search/search-pagination-page-layout#hit-highlighting ("By default, the markup
# format is <em>, but you can override the tag by using the highlightPreTag and highlightPostTag parameters")
def test_highlights_wrap_matched_terms(hotels):
    results = list(hotels.search(search_text="pool", highlight_fields="description"))
    assert {r["id"] for r in results} == {"1", "3"}
    for result in results:
        fragments = result["@search.highlights"]["description"]
        assert any("<em>pool</em>" in f for f in fragments), fragments

    for result in hotels.search(search_text="pool", highlight_fields="description",
                                highlight_pre_tag="<b>", highlight_post_tag="</b>"):
        fragments = result["@search.highlights"]["description"]
        assert any("<b>pool</b>" in f for f in fragments), fragments
        assert not any("<em>" in f for f in fragments), fragments


# https://learn.microsoft.com/azure/search/index-add-suggesters
# https://learn.microsoft.com/rest/api/searchservice/documents/suggest-post (select: "If unspecified, only the key
# field will be included in the results"; fuzzy; filter; highlightPreTag)
def test_suggest_returns_matching_source_field_text(hotels):
    budget = hotels.suggest("bud", "sg")
    assert [s.text for s in budget] == ["Budget Inn"]
    assert _support.fields(budget[0]) == {"id": "2"}

    assert hotels.suggest("bud", "sg", select=["id", "category"])[0]["category"] == "Budget"
    assert [s.text for s in hotels.suggest("bidget", "sg", use_fuzzy_matching=True)] == ["Budget Inn"]
    assert {s.text for s in hotels.suggest("b", "sg")} == {"Budget Inn", "Harbor Boutique"}
    assert [s.text for s in hotels.suggest("b", "sg", filter="category eq 'Boutique'")] == ["Harbor Boutique"]

    highlighted = hotels.suggest("bud", "sg", highlight_pre_tag="<b>", highlight_post_tag="</b>")[0].text
    assert "<b>" in highlighted and "</b>" in highlighted
    assert highlighted.replace("<b>", "").replace("</b>", "") == "Budget Inn"


# https://learn.microsoft.com/rest/api/searchservice/documents/autocomplete-post (oneTerm, twoTerms,
# oneTermWithContext; text "The completed term", queryPlusText "The query along with the completed term")
def test_autocomplete_completes_terms_in_each_mode(hotels):
    one_term = hotels.autocomplete("moun", "sg")
    assert [(i.text.lower(), i.query_plus_text.lower()) for i in one_term] == [("mountain", "mountain")]

    last_term = hotels.autocomplete("budget i", "sg", mode="oneTerm")
    assert [(i.text.lower(), i.query_plus_text.lower()) for i in last_term] == [("inn", "budget inn")]

    two_terms = hotels.autocomplete("moun", "sg", mode="twoTerms")
    assert "mountain lodge" in [i.text.lower() for i in two_terms]

    with_context = hotels.autocomplete("harbor b", "sg", mode="oneTermWithContext")
    assert [i.query_plus_text.lower() for i in with_context] == ["harbor boutique"]


# https://learn.microsoft.com/rest/api/searchservice/documents/suggest-post (suggesterName: "The name of the suggester
# as specified in the suggesters collection that's part of the index definition")
def test_suggest_with_an_unknown_suggester_is_a_bad_request(hotels):
    assert status_of(lambda: hotels.suggest("bud", "no-such-suggester")) == 400


# https://learn.microsoft.com/azure/search/search-synonyms (equivalency and explicit "=>" rules; "If the synonym map
# exists on the search service, it's used on the next query, with no reindexing or rebuild required"; "if you then
# delete a synonym map, any query that includes the fields in question fails with a 404 error")
def test_synonym_maps_expand_queries_on_assigned_fields(service, index_client):
    map_name = _support.name("syn")
    index_name = _support.name("synonyms")
    created = index_client.create_synonym_map(SynonymMap(
        name=map_name, synonyms=["dog, canine, puppy", "Washington, Wash., WA => WA"]))
    try:
        assert created.name == map_name
        assert created.format == "solr"
        assert created.e_tag
        assert map_name in index_client.get_synonym_map_names()

        index_client.create_index(SearchIndex(name=index_name, fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
            SearchField(name="description", type=SearchFieldDataType.STRING, searchable=True),
        ]))
        client = _support.search_client(service, index_name)
        client.upload_documents([
            {"id": "dog", "description": "a friendly dog"},
            {"id": "wa", "description": "visiting WA state"},
            {"id": "washington", "description": "the Washington monument"},
        ])
        eventually(lambda: _assert_equal(client.get_document_count(), 3))
        assert search_keys(client, "canine") == [], "no synonyms before the map is assigned"

        index = index_client.get_index(index_name)
        index.fields[1].synonym_map_names = [map_name]
        index_client.create_or_update_index(index)

        eventually(lambda: _assert_equal(search_keys(client, "canine"), ["dog"]))
        assert search_keys(client, "puppy") == ["dog"]
        # Scoped to the synonym-enabled field: "id" is searchable too ("Fields of type Edm.String or
        # Collection(Edm.String) are searchable by default", https://learn.microsoft.com/rest/api/searchservice/indexes/create)
        # and synonyms apply "only for fields that have a synonym assignment"
        # (https://learn.microsoft.com/azure/search/search-synonyms), so an unscoped query also matches the key
        # "washington" through the id field.
        assert search_keys(client, "Washington", search_fields=["description"]) == ["wa"], \
            "the explicit mapping rewrites the query to WA only"
        assert search_keys(client, "monument") == ["washington"]

        index_client.delete_synonym_map(map_name)
        eventually(lambda: _assert_equal(status_of(lambda: search_keys(client, "canine")), 404))
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)
        _support.delete_quietly(index_client.delete_synonym_map, map_name)


# https://learn.microsoft.com/azure/search/index-add-scoring-profiles (weighted fields; magnitude functions)
# https://learn.microsoft.com/rest/api/searchservice/indexes/create (defaultScoringProfile: "The name of the scoring
# profile to use if none is specified in the query")
def test_scoring_profiles_boost_weighted_fields_and_magnitudes(service, index_client):
    index_name = _support.name("scoring")
    index_client.create_index(SearchIndex(
        name=index_name,
        fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
            SearchField(name="title", type=SearchFieldDataType.STRING, searchable=True),
            SearchField(name="body", type=SearchFieldDataType.STRING, searchable=True),
            SearchField(name="rating", type=SearchFieldDataType.INT32, filterable=True, sortable=True),
        ],
        scoring_profiles=[
            ScoringProfile(name="titleBoost", text_weights=TextWeights(weights={"title": 5.0})),
            ScoringProfile(name="ratingBoost", functions=[MagnitudeScoringFunction(
                field_name="rating", boost=10.0, interpolation="linear",
                parameters=MagnitudeScoringParameters(boosting_range_start=1, boosting_range_end=5))]),
        ],
        default_scoring_profile="titleBoost",
    ))
    try:
        client = _support.search_client(service, index_name)
        client.upload_documents([
            {"id": "in-title", "title": "harbor view", "body": "quiet inn", "rating": 1},
            {"id": "in-body", "title": "quiet inn", "body": "harbor view", "rating": 1},
            {"id": "low", "title": "lake cabin", "body": "pine forest", "rating": 1},
            {"id": "high", "title": "lake cabin", "body": "pine forest", "rating": 5},
        ])
        eventually(lambda: _assert_equal(client.get_document_count(), 4))

        assert search_keys(client, "harbor", scoring_profile="titleBoost") == ["in-title", "in-body"]
        assert search_keys(client, "harbor") == ["in-title", "in-body"], "the default scoring profile applies"
        assert search_keys(client, "cabin", scoring_profile="ratingBoost") == ["high", "low"]
        assert status_of(lambda: search_keys(client, "cabin", scoring_profile="no-such-profile")) == 400
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


# https://learn.microsoft.com/azure/search/search-how-to-alias ("You can use an alias for all document operations";
# "An update to an alias might take up to 10 seconds to propagate"; deleting an index an alias maps to "fails with
# 400 (Bad Request)")
def test_aliases_route_document_operations_and_can_be_repointed(service, index_client):
    v1, v2, alias_name = _support.name("alias-v1"), _support.name("alias-v2"), _support.name("alias")
    for index_name in (v1, v2):
        index_client.create_index(SearchIndex(name=index_name, fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
            SearchField(name="version", type=SearchFieldDataType.STRING, searchable=True),
        ]))
    try:
        _support.search_client(service, v1).upload_documents([{"id": "from-v1", "version": "one"}])
        _support.search_client(service, v2).upload_documents([{"id": "from-v2", "version": "two"}])

        created = index_client.create_alias(SearchAlias(name=alias_name, indexes=[v1]))
        assert (created.name, created.indexes) == (alias_name, [v1])
        assert index_client.get_alias(alias_name).indexes == [v1]
        assert alias_name in list(index_client.list_alias_names())

        via_alias = _support.search_client(service, alias_name)
        eventually(lambda: _assert_equal(search_keys(via_alias, "*"), ["from-v1"]))
        via_alias.upload_documents([{"id": "written-through-alias", "version": "one"}])
        eventually(lambda: _assert_equal(
            _support.search_client(service, v1).get_document("written-through-alias")["version"], "one"))

        index_client.create_or_update_alias(SearchAlias(name=alias_name, indexes=[v2]))
        eventually(lambda: _assert_equal(search_keys(via_alias, "*"), ["from-v2"]), timeout=30)

        assert status_of(lambda: index_client.delete_index(v2)) == 400

        index_client.delete_alias(alias_name)
        eventually(lambda: _assert_equal(status_of(lambda: search_keys(via_alias, "*")), 404), timeout=30)
    finally:
        _support.delete_quietly(index_client.delete_alias, alias_name)
        _support.delete_quietly(index_client.delete_index, v1)
        _support.delete_quietly(index_client.delete_index, v2)


# https://learn.microsoft.com/rest/api/searchservice/indexes/analyze (sample: "Text to analyze" gives text 0-4, to 5-7,
# analyze 8-15 at positions 0, 1, 2)
# https://learn.microsoft.com/azure/search/index-add-custom-analyzers (standard, keyword, whitespace and stop analyzers)
def test_analyze_api_tokenizes_with_predefined_analyzers(index_client, hotels_index):
    tokens = index_client.analyze_text(hotels_index, AnalyzeTextOptions(
        text="Text to analyze", analyzer_name="standard.lucene")).tokens
    assert [(t.token, t.start_offset, t.end_offset, t.position) for t in tokens] == [
        ("text", 0, 4, 0), ("to", 5, 7, 1), ("analyze", 8, 15, 2)]

    def analyze(analyzer):
        result = index_client.analyze_text(hotels_index, AnalyzeTextOptions(
            text="The Quick-Brown fox", analyzer_name=analyzer))
        return [t.token for t in result.tokens]

    assert analyze("standard.lucene") == ["the", "quick", "brown", "fox"]
    assert analyze("keyword") == ["The Quick-Brown fox"]
    assert analyze("whitespace") == ["The", "Quick-Brown", "fox"]
    assert analyze("stop") == ["quick", "brown", "fox"]

    custom_chain = index_client.analyze_text(hotels_index, AnalyzeTextOptions(
        text="The Quick-Brown fox", tokenizer_name="whitespace", token_filters=["lowercase"]))
    assert [t.token for t in custom_chain.tokens] == ["the", "quick-brown", "fox"]


# https://learn.microsoft.com/azure/search/index-add-custom-analyzers (a custom analyzer combines a tokenizer, token
# filters and char filters: html_strip, asciifolding, lowercase)
def test_custom_analyzers_apply_to_analyze_and_indexed_fields(service, index_client):
    index_name = _support.name("custom")
    index_client.create_index(SearchIndex(
        name=index_name,
        fields=[
            SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
            SearchField(name="note", type=SearchFieldDataType.STRING, searchable=True, analyzer_name="folding"),
        ],
        analyzers=[CustomAnalyzer(name="folding", tokenizer_name="standard_v2",
                                  token_filters=["lowercase", "asciifolding"], char_filters=["html_strip"])],
    ))
    try:
        assert "folding" in [a.name for a in index_client.get_index(index_name).analyzers]
        folded = index_client.analyze_text(index_name, AnalyzeTextOptions(
            text="<p>Café DÉJÀ vu</p>", analyzer_name="folding")).tokens
        assert [t.token for t in folded] == ["cafe", "deja", "vu"]

        client = _support.search_client(service, index_name)
        client.upload_documents([{"id": "dessert", "note": "Crème brûlée at the <b>Café</b>"}])
        eventually(lambda: _assert_equal(search_keys(client, "creme brulee", search_mode="all"), ["dessert"]))
        assert search_keys(client, "b") == [], "html_strip removed the markup before tokenizing"
    finally:
        _support.delete_quietly(index_client.delete_index, index_name)


def _assert_equal(actual, expected):
    assert actual == expected
