package io.floci.az.services.search.engine;

import java.util.Optional;

/**
 * The emulator settings the engine reads, copied from {@code floci-az.services.search} so the engine stays free of
 * Quarkus.
 *
 * @param maxDocumentsPerIndex  the most documents one index holds (an emulator guard, not an Azure quota)
 * @param semanticRankerEnabled whether semantic queries are answered (by the stand-in) or refused as on a service with
 *                              semantic ranking disabled
 * @param modelEndpointUrl      the OpenAI-compatible endpoint vectorizers call, or null when none is configured
 */
public record EngineSettings(int maxDocumentsPerIndex, boolean semanticRankerEnabled, String modelEndpointUrl) {

    /** The defaults of {@code application.yml}, for engine tests. */
    public static final EngineSettings DEFAULTS = new EngineSettings(100_000, true, null);

    /** The configured model endpoint, if any. */
    public Optional<String> modelEndpoint() {
        return Optional.ofNullable(modelEndpointUrl);
    }
}
