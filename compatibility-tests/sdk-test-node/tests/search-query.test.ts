import { SearchClient, SearchIndexClient } from "@azure/search-documents";
import {
  Hotel,
  createHotels,
  deleteIndexQuietly,
  eventually,
  indexClient,
  searchAll,
  searchClient,
  searchKeys,
  serviceName,
  statusOf,
  uniqueName,
} from "./searchSupport";

// Azure AI Search query features through @azure/search-documents: the full Lucene syntax, facets, hit
// highlighting, suggestions and autocomplete, synonym maps, scoring profiles, index aliases, the analyze API
// and custom analyzers.

interface Doc {
  id: string;
  description?: string;
  title?: string;
  body?: string;
  rating?: number;
  version?: string;
  note?: string;
}

function facetProperty(facet: Record<string, unknown>, property: string): unknown {
  const additional = facet.additionalProperties as Record<string, unknown> | undefined;
  return facet[property] ?? additional?.[property];
}

describe("search-query: Lucene syntax, facets, suggestions, synonyms, scoring, aliases and analyzers", () => {
  const service = serviceName("nodequery");
  let indexes: SearchIndexClient;
  let hotelsName: string;
  let hotels: SearchClient<Hotel>;

  beforeAll(async () => {
    indexes = indexClient(service);
    ({ name: hotelsName, client: hotels } = await createHotels(service));
  });

  afterAll(async () => {
    if (hotelsName) {
      await deleteIndexQuietly(indexes, hotelsName);
    }
  });

  const lucene = (query: string) => searchKeys(hotels, query, { queryType: "full" });

  // https://learn.microsoft.com/azure/search/query-lucene-syntax (fielded search, Boolean operators, NOT, wildcard,
  // regular expression "/[mh]otel/", fuzzy "blue~1", proximity "\"hotel airport\"~5")
  test("full Lucene syntax", async () => {
    expect(await lucene("name:lodge")).toEqual(["4"]);
    expect(new Set(await lucene("pool AND view"))).toEqual(new Set(["1"]));
    expect(new Set(await lucene("pool OR view"))).toEqual(new Set(["1", "3", "6"]));
    expect(new Set(await lucene("pool -rooftop"))).toEqual(new Set(["1"]));
    expect(new Set(await lucene("mo*n"))).toEqual(new Set(["3", "4"]));
    expect(await lucene("l?dge")).toEqual(["4"]);
    expect(new Set(await lucene("/.*port/"))).toEqual(new Set(["2", "5"]));
    expect(new Set(await lucene("/[mh]otel/"))).toEqual(new Set(["2", "3", "6"]));
    expect(await lucene("montain~1")).toEqual(["4"]);
    expect(await lucene('"hotel harbor"~5')).toEqual(["6"]);
    expect(await lucene('"hotel harbor"')).toEqual([]);
  });

  // https://learn.microsoft.com/azure/search/search-faceted-navigation (count, sort:value ascending, values buckets
  // "one for base rate 0 up to but not including 10, one for 10 up to but not including 20, one for 20 and above",
  // interval)
  test("facets: values, ranges and intervals", async () => {
    const response = await hotels.search("*", {
      facets: ["category,count:10,sort:value", "tags", "price,values:100|200", "rating,interval:2"],
    });
    const facets = response.facets ?? {};
    const categories = (facets.category ?? []) as Record<string, unknown>[];
    expect(categories.map((f) => [facetProperty(f, "value"), f.count])).toEqual([
      ["Boutique", 1], ["Budget", 2], ["Luxury", 1], ["Resort", 2],
    ]);
    const tags = Object.fromEntries(((facets.tags ?? []) as Record<string, unknown>[])
      .map((f) => [String(facetProperty(f, "value")), f.count]));
    expect(tags).toEqual({ wifi: 3, pool: 2, view: 2, parking: 1, gym: 1, hiking: 1, fireplace: 1, shuttle: 1, boutique: 1 });
    const prices = (facets.price ?? []) as Record<string, unknown>[];
    expect(prices.map((f) => f.count)).toEqual([2, 3, 1]);
    expect(facetProperty(prices[0], "to")).toBe(100);
    expect([facetProperty(prices[1], "from"), facetProperty(prices[1], "to")]).toEqual([100, 200]);
    expect(facetProperty(prices[2], "from")).toBe(200);
    const ratings = Object.fromEntries(((facets.rating ?? []) as Record<string, unknown>[])
      .map((f) => [String(facetProperty(f, "value")), f.count]));
    expect(ratings).toEqual({ "2": 3, "4": 3 });
  });

  // https://learn.microsoft.com/azure/search/search-pagination-page-layout#hit-highlighting ("By default, the markup
  // format is <em>, but you can override the tag by using the highlightPreTag and highlightPostTag parameters")
  test("highlights wrap the matched terms", async () => {
    const results = await searchAll(hotels, "pool", { highlightFields: "description" });
    expect(new Set(results.map((r) => r.document.id))).toEqual(new Set(["1", "3"]));
    for (const result of results) {
      expect(result.highlights?.description?.some((f) => f.includes("<em>pool</em>"))).toBe(true);
    }
    const custom = await searchAll(hotels, "pool", {
      highlightFields: "description", highlightPreTag: "<b>", highlightPostTag: "</b>",
    });
    for (const result of custom) {
      expect(result.highlights?.description?.some((f) => f.includes("<b>pool</b>"))).toBe(true);
    }
  });

  // https://learn.microsoft.com/azure/search/index-add-suggesters
  // https://learn.microsoft.com/rest/api/searchservice/documents/suggest-post (select: "If unspecified, only the key
  // field will be included in the results"; fuzzy; filter)
  // https://learn.microsoft.com/rest/api/searchservice/documents/autocomplete-post (oneTerm, twoTerms,
  // oneTermWithContext)
  test("suggest and autocomplete", async () => {
    const budget = await hotels.suggest("bud", "sg");
    expect(budget.results.map((r) => r.text)).toEqual(["Budget Inn"]);
    expect((budget.results[0].document as Record<string, unknown>).id).toBe("2");
    const fuzzy = await hotels.suggest("bidget", "sg", { useFuzzyMatching: true });
    expect(fuzzy.results.map((r) => r.text)).toEqual(["Budget Inn"]);
    const filtered = await hotels.suggest("b", "sg", { filter: "category eq 'Boutique'" });
    expect(filtered.results.map((r) => r.text)).toEqual(["Harbor Boutique"]);

    const oneTerm = await hotels.autocomplete("moun", "sg");
    expect(oneTerm.results.map((r) => [r.text.toLowerCase(), r.queryPlusText.toLowerCase()]))
      .toEqual([["mountain", "mountain"]]);
    const lastTerm = await hotels.autocomplete("budget i", "sg", { autocompleteMode: "oneTerm" });
    expect(lastTerm.results.map((r) => r.queryPlusText.toLowerCase())).toEqual(["budget inn"]);
    const twoTerms = await hotels.autocomplete("moun", "sg", { autocompleteMode: "twoTerms" });
    expect(twoTerms.results.map((r) => r.text.toLowerCase())).toContain("mountain lodge");
  });

  // https://learn.microsoft.com/azure/search/search-synonyms (equivalency and explicit "=>" rules; "If the synonym map
  // exists on the search service, it's used on the next query, with no reindexing or rebuild required")
  test("synonym maps expand queries on the fields they are assigned to", async () => {
    const mapName = uniqueName("syn");
    const indexName = uniqueName("synonyms");
    const created = await indexes.createSynonymMap({
      name: mapName, synonyms: ["dog, canine, puppy", "Washington, Wash., WA => WA"],
    });
    try {
      expect(created.name).toBe(mapName);
      expect(await indexes.listSynonymMapsNames()).toContain(mapName);
      await indexes.createIndex({
        name: indexName,
        fields: [
          { name: "id", type: "Edm.String", key: true },
          { name: "description", type: "Edm.String", searchable: true },
        ],
      });
      const client = searchClient<Doc>(service, indexName);
      await client.uploadDocuments([
        { id: "dog", description: "a friendly dog" },
        { id: "wa", description: "visiting WA state" },
        { id: "washington", description: "the Washington monument" },
      ]);
      await eventually(async () => {
        expect(await client.getDocumentsCount()).toBe(3);
      });
      expect(await searchKeys(client, "canine")).toEqual([]);

      const index = await indexes.getIndex(indexName);
      index.fields = index.fields.map((field) =>
        field.name === "description" ? { ...field, synonymMapNames: [mapName] } : field);
      await indexes.createOrUpdateIndex(index);
      await eventually(async () => {
        expect(await searchKeys(client, "canine")).toEqual(["dog"]);
      });
      expect(await searchKeys(client, "Washington")).toEqual(["wa"]);
    } finally {
      await deleteIndexQuietly(indexes, indexName);
      await indexes.deleteSynonymMap(mapName).catch(() => undefined);
    }
  });

  // https://learn.microsoft.com/azure/search/index-add-scoring-profiles (weighted fields; magnitude functions)
  // https://learn.microsoft.com/rest/api/searchservice/indexes/create (defaultScoringProfile)
  test("scoring profiles boost weighted fields and magnitudes", async () => {
    const indexName = uniqueName("scoring");
    await indexes.createIndex({
      name: indexName,
      fields: [
        { name: "id", type: "Edm.String", key: true },
        { name: "title", type: "Edm.String", searchable: true },
        { name: "body", type: "Edm.String", searchable: true },
        { name: "rating", type: "Edm.Int32", filterable: true, sortable: true },
      ],
      scoringProfiles: [
        { name: "titleBoost", textWeights: { weights: { title: 5 } } },
        {
          name: "ratingBoost",
          functions: [{
            type: "magnitude", fieldName: "rating", boost: 10, interpolation: "linear",
            parameters: { boostingRangeStart: 1, boostingRangeEnd: 5 },
          }],
        },
      ],
      defaultScoringProfile: "titleBoost",
    });
    try {
      const client = searchClient<Doc>(service, indexName);
      await client.uploadDocuments([
        { id: "in-title", title: "harbor view", body: "quiet inn", rating: 1 },
        { id: "in-body", title: "quiet inn", body: "harbor view", rating: 1 },
        { id: "low", title: "lake cabin", body: "pine forest", rating: 1 },
        { id: "high", title: "lake cabin", body: "pine forest", rating: 5 },
      ]);
      await eventually(async () => {
        expect(await client.getDocumentsCount()).toBe(4);
      });
      expect(await searchKeys(client, "harbor", { scoringProfile: "titleBoost" })).toEqual(["in-title", "in-body"]);
      expect(await searchKeys(client, "harbor")).toEqual(["in-title", "in-body"]);
      expect(await searchKeys(client, "cabin", { scoringProfile: "ratingBoost" })).toEqual(["high", "low"]);
    } finally {
      await deleteIndexQuietly(indexes, indexName);
    }
  });

  // https://learn.microsoft.com/azure/search/search-how-to-alias ("You can use an alias for all document operations";
  // "An update to an alias might take up to 10 seconds to propagate"; deleting a mapped index "fails with 400")
  test("aliases route document operations and can be repointed", async () => {
    const v1 = uniqueName("alias-v1");
    const v2 = uniqueName("alias-v2");
    const aliasName = uniqueName("alias");
    for (const name of [v1, v2]) {
      await indexes.createIndex({
        name,
        fields: [
          { name: "id", type: "Edm.String", key: true },
          { name: "version", type: "Edm.String", searchable: true },
        ],
      });
    }
    try {
      await searchClient<Doc>(service, v1).uploadDocuments([{ id: "from-v1", version: "one" }]);
      await searchClient<Doc>(service, v2).uploadDocuments([{ id: "from-v2", version: "two" }]);
      const created = await indexes.createAlias({ name: aliasName, indexes: [v1] });
      expect(created.indexes).toEqual([v1]);
      expect((await indexes.getAlias(aliasName)).indexes).toEqual([v1]);

      const viaAlias = searchClient<Doc>(service, aliasName);
      await eventually(async () => {
        expect(await searchKeys(viaAlias, "*")).toEqual(["from-v1"]);
      });
      await indexes.createOrUpdateAlias({ name: aliasName, indexes: [v2] });
      await eventually(async () => {
        expect(await searchKeys(viaAlias, "*")).toEqual(["from-v2"]);
      }, 30_000);
      expect(await statusOf(() => indexes.deleteIndex(v2))).toBe(400);
    } finally {
      await indexes.deleteAlias(aliasName).catch(() => undefined);
      await deleteIndexQuietly(indexes, v1);
      await deleteIndexQuietly(indexes, v2);
    }
  }, 90_000);

  // https://learn.microsoft.com/rest/api/searchservice/indexes/analyze (sample: "Text to analyze" gives text 0-4,
  // to 5-7, analyze 8-15)
  // https://learn.microsoft.com/azure/search/index-add-custom-analyzers (predefined analyzers; a custom analyzer
  // combines a tokenizer, token filters and char filters)
  test("analyze API with predefined and custom analyzers", async () => {
    const standard = await indexes.analyzeText(hotelsName, { text: "Text to analyze", analyzerName: "standard.lucene" });
    expect(standard.tokens.map((t) => [t.token, t.startOffset, t.endOffset, t.position]))
      .toEqual([["text", 0, 4, 0], ["to", 5, 7, 1], ["analyze", 8, 15, 2]]);
    const tokens = async (analyzerName: string) =>
      (await indexes.analyzeText(hotelsName, { text: "The Quick-Brown fox", analyzerName })).tokens.map((t) => t.token);
    expect(await tokens("keyword")).toEqual(["The Quick-Brown fox"]);
    expect(await tokens("whitespace")).toEqual(["The", "Quick-Brown", "fox"]);
    expect(await tokens("stop")).toEqual(["quick", "brown", "fox"]);

    const indexName = uniqueName("custom");
    await indexes.createIndex({
      name: indexName,
      fields: [
        { name: "id", type: "Edm.String", key: true },
        { name: "note", type: "Edm.String", searchable: true, analyzerName: "folding" },
      ],
      analyzers: [{
        odatatype: "#Microsoft.Azure.Search.CustomAnalyzer", name: "folding", tokenizerName: "standard_v2",
        tokenFilters: ["lowercase", "asciifolding"], charFilters: ["html_strip"],
      }],
    });
    try {
      const folded = await indexes.analyzeText(indexName, { text: "<p>Café DÉJÀ vu</p>", analyzerName: "folding" });
      expect(folded.tokens.map((t) => t.token)).toEqual(["cafe", "deja", "vu"]);
      const client = searchClient<Doc>(service, indexName);
      await client.uploadDocuments([{ id: "dessert", note: "Crème brûlée at the <b>Café</b>" }]);
      await eventually(async () => {
        expect(await searchKeys(client, "creme brulee", { searchMode: "all" })).toEqual(["dessert"]);
      });
    } finally {
      await deleteIndexQuietly(indexes, indexName);
    }
  });
});
