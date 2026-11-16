import { SearchClient, SearchIndexClient, SimpleField, VectorizedQuery } from "@azure/search-documents";
import {
  deleteIndexQuietly,
  eventually,
  indexClient,
  searchAll,
  searchClient,
  serviceName,
  uniqueName,
} from "./searchSupport";

// Azure AI Search vector search through @azure/search-documents: vector fields with exhaustive KNN and HNSW
// profiles, k-nearest-neighbour queries, filtered vector queries, hybrid queries and multiple vector queries.
// The three-dimensional vectors make the cosine similarities to the query vectors distinct, and HNSW fields are
// queried with exhaustive: true, which the docs define as an exact search.

interface VectorDoc {
  id: string;
  title: string;
  category: string;
  titleVector: number[];
  contentVector: number[];
}

const X_AXIS = [1, 0, 0];

const DOCUMENTS: VectorDoc[] = [
  { id: "1", title: "red apple", category: "fruit", titleVector: [1, 0, 0], contentVector: [1, 0, 0] },
  { id: "2", title: "green apple", category: "fruit", titleVector: [0.9, 0.1, 0], contentVector: [0, 1, 0] },
  { id: "3", title: "blue car", category: "vehicle", titleVector: [0.2, 1, 0], contentVector: [0.9, 0.1, 0] },
  { id: "4", title: "red car", category: "vehicle", titleVector: [0, 0, 1], contentVector: [0, 0, 1] },
  { id: "5", title: "yellow banana", category: "fruit", titleVector: [0.5, 0.5, 0], contentVector: [0.3, 0.3, 0.9] },
];

function exact(vector: number[], fields: ("titleVector" | "contentVector")[], k: number): VectorizedQuery<VectorDoc> {
  return { kind: "vector", vector, fields, kNearestNeighborsCount: k, exhaustive: true };
}

describe("search-vector: vector, filtered, hybrid and multi-vector queries", () => {
  const service = serviceName("nodevector");
  const indexName = uniqueName("vectors");
  let indexes: SearchIndexClient;
  let client: SearchClient<VectorDoc>;

  // No search text makes a pure vector query: "*" would add a full-text leg and turn it into a hybrid query.
  const ids = async (searchText: string | undefined, options: Parameters<SearchClient<VectorDoc>["search"]>[1]) =>
    (await searchAll(client, searchText, options)).map((r) => r.document.id);

  beforeAll(async () => {
    indexes = indexClient(service);
    await indexes.createIndex({
      name: indexName,
      fields: [
        { name: "id", type: "Edm.String", key: true },
        { name: "title", type: "Edm.String", searchable: true },
        { name: "category", type: "Edm.String", filterable: true },
        {
          name: "titleVector", type: "Collection(Edm.Single)", searchable: true,
          vectorSearchDimensions: 3, vectorSearchProfileName: "exhaustive-profile",
        },
        {
          name: "contentVector", type: "Collection(Edm.Single)", searchable: true,
          vectorSearchDimensions: 3, vectorSearchProfileName: "hnsw-profile",
        },
      ],
      vectorSearch: {
        algorithms: [
          { name: "eknn", kind: "exhaustiveKnn", parameters: { metric: "cosine" } },
          { name: "hnsw", kind: "hnsw", parameters: { metric: "cosine", m: 4, efConstruction: 400, efSearch: 500 } },
        ],
        profiles: [
          { name: "exhaustive-profile", algorithmConfigurationName: "eknn" },
          { name: "hnsw-profile", algorithmConfigurationName: "hnsw" },
        ],
      },
    });
    client = searchClient<VectorDoc>(service, indexName);
    await client.uploadDocuments(DOCUMENTS);
    await eventually(async () => {
      expect(await client.getDocumentsCount()).toBe(DOCUMENTS.length);
    });
  });

  afterAll(async () => {
    await deleteIndexQuietly(indexes, indexName);
  });

  // https://learn.microsoft.com/azure/search/vector-search-how-to-create-index (vector fields and profiles)
  test("vector fields and profiles round-trip", async () => {
    const index = await indexes.getIndex(indexName);
    const algorithms = Object.fromEntries((index.vectorSearch?.algorithms ?? []).map((a) => [a.name, a.kind]));
    expect(algorithms).toEqual({ eknn: "exhaustiveKnn", hnsw: "hnsw" });
    const profiles = Object.fromEntries((index.vectorSearch?.profiles ?? [])
      .map((p) => [p.name, p.algorithmConfigurationName]));
    expect(profiles).toEqual({ "exhaustive-profile": "eknn", "hnsw-profile": "hnsw" });
    const titleVector = index.fields.find((f) => f.name === "titleVector") as SimpleField;
    expect([titleVector.vectorSearchDimensions, titleVector.vectorSearchProfileName]).toEqual([3, "exhaustive-profile"]);
  });

  // https://learn.microsoft.com/azure/search/vector-search-how-to-query ("Vector queries always return k results";
  // "If k is larger than the number of documents in the index, the number of documents determines the upper limit")
  // https://learn.microsoft.com/azure/search/vector-search-ranking (cosine @search.score range "0.333 - 1.00"; "Score
  // values always decrease as the similarity gets worse")
  test("k nearest neighbours come back in similarity order", async () => {
    const results = await searchAll(client, undefined, { vectorSearchOptions: { queries: [exact(X_AXIS, ["titleVector"], 3)] } });
    expect(results.map((r) => r.document.id)).toEqual(["1", "2", "5"]);
    const scores = results.map((r) => r.score);
    expect(scores.every((s) => s >= 0.333 && s <= 1.000001)).toBe(true);
    expect([...scores].sort((a, b) => b - a)).toEqual(scores);

    const dissimilar = await ids(undefined, { vectorSearchOptions: { queries: [exact([0, 0, 1], ["titleVector"], 2)] } });
    expect(dissimilar).toHaveLength(2);
    expect(dissimilar[0]).toBe("4");
    expect(new Set(await ids(undefined, { vectorSearchOptions: { queries: [exact(X_AXIS, ["titleVector"], 50)] } })))
      .toEqual(new Set(["1", "2", "3", "4", "5"]));
  });

  // https://learn.microsoft.com/azure/search/vector-search-how-to-query ("vectorQueries.exhaustive (optional) invokes
  // exhaustive KNN at query time, even if the field is indexed for HNSW")
  // https://learn.microsoft.com/azure/search/vector-search-filters (preFilter applies the filter before the vector
  // search)
  test("exhaustive queries on HNSW fields and pre-filtered queries", async () => {
    expect(await ids(undefined, { vectorSearchOptions: { queries: [exact(X_AXIS, ["contentVector"], 2)] } }))
      .toEqual(["1", "3"]);
    expect(await ids(undefined, {
      filter: "category eq 'vehicle'",
      vectorSearchOptions: { queries: [exact(X_AXIS, ["titleVector"], 2)], filterMode: "preFilter" },
    })).toEqual(["3", "4"]);
  });

  // https://learn.microsoft.com/azure/search/hybrid-search-ranking ("documents appearing in the top positions across
  // multiple search methods ... should be ranked higher")
  // https://learn.microsoft.com/azure/search/vector-search-how-to-query (multiple vector queries are fused with RRF)
  test("hybrid and multi-vector queries are fused with RRF", async () => {
    const hybrid = await ids("red apple", {
      searchFields: ["title"],
      vectorSearchOptions: { queries: [exact(X_AXIS, ["titleVector"], 3)] },
    });
    expect(hybrid.slice(0, 2)).toEqual(["1", "2"]);
    expect(new Set(hybrid)).toEqual(new Set(["1", "2", "4", "5"]));

    const multi = await ids(undefined, {
      vectorSearchOptions: { queries: [exact(X_AXIS, ["titleVector"], 3), exact(X_AXIS, ["contentVector"], 3)] },
    });
    expect(multi.slice(0, 2)).toEqual(["1", "5"]);
    expect(new Set(multi)).toEqual(new Set(["1", "2", "3", "5"]));
  });

  // https://learn.microsoft.com/azure/search/vector-search-how-to-query#vector-weighting (a query's weight multiplies
  // its reciprocal rank scores when the lists are fused)
  test("weights multiply each list's reciprocal rank score", async () => {
    expect(await ids(undefined, {
      vectorSearchOptions: {
        queries: [exact(X_AXIS, ["titleVector"], 2), { ...exact(X_AXIS, ["contentVector"], 2), weight: 3 }],
      },
    })).toEqual(["1", "3", "2"]);
  });});
