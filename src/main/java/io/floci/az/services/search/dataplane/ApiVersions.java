package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The data-plane API versions the service accepts: every stable and preview version published under
 * {@code specification/search/data-plane/Search} in azure-rest-api-specs, which covers what current and older
 * azure-search-documents SDKs send. Versions compare ignoring case ({@code 2020-06-30-Preview}).
 */
public final class ApiVersions {

    /** The stable versions, oldest first. */
    public static final List<String> STABLE = List.of(
        "2015-02-28", "2016-09-01", "2017-11-11", "2019-05-06", "2020-06-30", "2023-11-01", "2024-07-01",
        "2025-09-01", "2026-04-01", "2026-10-01");

    /** The preview versions, oldest first. */
    public static final List<String> PREVIEW = List.of(
        "2015-02-28-preview", "2016-09-01-preview", "2017-11-11-preview", "2019-05-06-preview",
        "2020-06-30-preview", "2021-04-30-preview", "2023-07-01-preview", "2023-10-01-preview",
        "2024-03-01-preview", "2024-05-01-preview", "2024-09-01-preview", "2024-11-01-preview",
        "2025-03-01-preview", "2025-05-01-preview", "2025-08-01-preview", "2025-11-01-preview",
        "2026-05-01-preview", "2026-08-01-preview", "2026-11-01-preview");

    private static final Set<String> SUPPORTED = supported();

    private static final String MESSAGE = "Invalid or missing api-version query string parameter.";

    private ApiVersions() {
    }

    private static Set<String> supported() {
        Set<String> versions = new HashSet<>(STABLE);
        versions.addAll(PREVIEW);
        return Set.copyOf(versions);
    }

    /** {@code value} when it is a supported version; a 400 when it is missing or unknown. */
    public static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new SearchException(SearchErrorCode.MISSING_API_VERSION, MESSAGE);
        }
        if (!SUPPORTED.contains(value.trim().toLowerCase(Locale.ROOT))) {
            throw new SearchException(SearchErrorCode.INVALID_API_VERSION, MESSAGE + " The api-version '" + value
                + "' is not supported.");
        }
        return value.trim();
    }

    /** Whether {@code version} is a preview version, for gating preview-only properties. */
    public static boolean isPreview(String version) {
        return version.toLowerCase(Locale.ROOT).endsWith("-preview");
    }
}
