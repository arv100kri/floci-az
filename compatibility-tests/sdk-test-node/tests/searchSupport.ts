import {
  AzureKeyCredential,
  SearchClient,
  SearchIndex,
  SearchIndexClient,
  SearchOptions,
  SearchResult,
} from "@azure/search-documents";
import { PipelinePolicy, RestError } from "@azure/core-rest-pipeline";

// Clients, names and fixtures shared by the Azure AI Search compatibility tests.
//
// The data plane is addressed path style, {FLOCI_AZ_ENDPOINT}/{service}-search; a data-plane request
// provisions the named service with floci-az's development keys (SearchServiceRegistry). The JS SDK
// refuses plain HTTP unless a request allows it, so every client carries a policy that does.

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";

export const ADMIN_KEY = "FlociAzSearchDevPrimaryAdminKey0000000000000000000000";
export const QUERY_KEY = "FlociAzSearchDevQueryKey000000000000000000000000000000";

/** Reference point for the geo tests: downtown Seattle, longitude first as in OData POINT(lon lat). */
export const SEATTLE = "geography'POINT(-122.335 47.608)'";

const allowHttpPolicy: PipelinePolicy = {
  name: "flociAzAllowHttp",
  sendRequest(request, next) {
    request.allowInsecureConnection = true;
    return next(request);
  },
};

const clientOptions = {
  additionalPolicies: [{ policy: allowHttpPolicy, position: "perCall" as const }],
};

function suffix(): string {
  return Math.random().toString(36).substring(2, 12).padEnd(10, "0");
}

/** Lowercase letters and digits only, so the -search path suffix stays unambiguous. */
export function serviceName(prefix: string): string {
  return `${prefix}${suffix()}`;
}

/** An index, alias or synonym map name. */
export function uniqueName(prefix: string): string {
  return `${prefix}-${suffix()}`;
}

export function endpoint(service: string): string {
  return `${BASE}/${service}-search`;
}

export function indexClient(service: string, key: string = ADMIN_KEY): SearchIndexClient {
  return new SearchIndexClient(endpoint(service), new AzureKeyCredential(key), clientOptions);
}

export function searchClient<T extends object>(
  service: string,
  indexOrAlias: string,
  key: string = ADMIN_KEY,
): SearchClient<T> {
  return new SearchClient<T>(endpoint(service), indexOrAlias, new AzureKeyCredential(key), clientOptions);
}

/** Every result of the query, following the service's continuation across pages; no search text is a pure vector query. */
export async function searchAll<T extends object>(
  client: SearchClient<T>,
  searchText: string | undefined,
  options?: SearchOptions<T>,
): Promise<SearchResult<T>[]> {
  const response = await client.search(searchText, options);
  const results: SearchResult<T>[] = [];
  for await (const result of response.results) {
    results.push(result);
  }
  return results;
}

/** Keys of the documents matching the query, in the order the service returned them. */
export async function searchKeys<T extends object>(
  client: SearchClient<T>,
  searchText: string,
  options?: SearchOptions<T>,
): Promise<string[]> {
  const results = await searchAll(client, searchText, options);
  return results.map((result) => String((result.document as Record<string, unknown>).id));
}

/** The HTTP status of the error the call throws. */
export async function statusOf(call: () => Promise<unknown>): Promise<number | undefined> {
  try {
    await call();
  } catch (error) {
    return (error as RestError).statusCode;
  }
  throw new Error("expected the call to fail");
}

/** Re-runs the assertion until it passes or the timeout elapses, then rethrows its last failure. */
export async function eventually(assertion: () => Promise<void>, timeoutMs = 15_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      await assertion();
      return;
    } catch (failure) {
      if (Date.now() > deadline) {
        throw failure;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
}

export async function deleteIndexQuietly(client: SearchIndexClient, name: string): Promise<void> {
  try {
    await client.deleteIndex(name);
  } catch {
    // Cleanup only: the index may already be gone, or the test failed before creating it.
  }
}

export interface Hotel {
  id: string;
  name?: string;
  description?: string;
  category?: string;
  tags?: string[];
  rating?: number;
  price?: number;
  parking?: boolean;
  location?: { type: "Point"; coordinates: number[] };
}

export function hotelsIndex(name: string): SearchIndex {
  return {
    name,
    fields: [
      { name: "id", type: "Edm.String", key: true, filterable: true, sortable: true, searchable: false },
      { name: "name", type: "Edm.String", searchable: true, filterable: true, sortable: true, facetable: false },
      { name: "description", type: "Edm.String", searchable: true, filterable: false, sortable: false, facetable: false },
      { name: "category", type: "Edm.String", searchable: false, filterable: true, sortable: true, facetable: true },
      { name: "tags", type: "Collection(Edm.String)", searchable: true, filterable: true, facetable: true },
      { name: "rating", type: "Edm.Int32", filterable: true, sortable: true, facetable: true },
      { name: "price", type: "Edm.Double", filterable: true, sortable: true, facetable: true },
      { name: "parking", type: "Edm.Boolean", filterable: true, facetable: true },
      { name: "location", type: "Edm.GeographyPoint", filterable: true, sortable: true },
    ],
    suggesters: [{ name: "sg", searchMode: "analyzingInfixMatching", sourceFields: ["name"] }],
  };
}

function hotel(
  id: string,
  name: string,
  description: string,
  category: string,
  tags: string[],
  rating: number,
  price: number,
  parking: boolean,
  longitude: number,
  latitude: number,
): Hotel {
  return {
    id, name, description, category, tags, rating, price, parking,
    location: { type: "Point", coordinates: [longitude, latitude] },
  };
}

/** Six hotels whose filters, sorts and distances have one right answer (the Java suite uses the same corpus). */
export const HOTELS: Hotel[] = [
  hotel("1", "Ocean View Resort", "Luxury resort with a pool and an ocean view", "Resort",
    ["pool", "view", "wifi"], 5, 250.0, true, -122.33, 47.61),
  hotel("2", "Budget Inn", "Cheap motel near the airport with free parking", "Budget",
    ["parking", "wifi"], 2, 60.0, true, -122.30, 47.45),
  hotel("3", "City Center Hotel", "Modern hotel in the city center with a rooftop pool", "Luxury",
    ["pool", "gym"], 4, 180.0, false, -122.34, 47.60),
  hotel("4", "Mountain Lodge", "Rustic lodge with hiking trails and a stone fireplace", "Resort",
    ["hiking", "fireplace"], 3, 120.0, true, -105.27, 40.01),
  hotel("5", "Airport Suites", "Convenient suites next to the airport terminal", "Budget",
    ["wifi", "shuttle"], 3, 90.0, false, -122.31, 47.44),
  hotel("6", "Harbor Boutique", "Small boutique hotel on the harbor with a view", "Boutique",
    ["boutique", "view"], 4, 150.0, false, -122.40, 47.65),
];

/** Creates the hotels index under a fresh name, uploads the corpus and waits until it is searchable. */
export async function createHotels(service: string): Promise<{ name: string; client: SearchClient<Hotel> }> {
  const name = uniqueName("hotels");
  await indexClient(service).createIndex(hotelsIndex(name));
  const client = searchClient<Hotel>(service, name);
  await client.uploadDocuments(HOTELS);
  await eventually(async () => {
    expect(await client.getDocumentsCount()).toBe(HOTELS.length);
  });
  return { name, client };
}
