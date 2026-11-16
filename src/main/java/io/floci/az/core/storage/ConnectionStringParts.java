package io.floci.az.core.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parsing shared by {@link StorageConnectionStrings} and {@link CosmosConnectionStrings}: the {@code key=value}
 * pairs, ARM resource ids and endpoint URLs of an indexer data source's connection string.
 */
final class ConnectionStringParts {

    /**
     * The DNS zones of Azure's public and sovereign clouds. A host in one of them names an Azure service endpoint,
     * so it is either the host-style endpoint a resolver recognizes or one outside floci-az.
     */
    private static final List<String> AZURE_ZONES = List.of(
            "windows.net", "azure.com", "azure.net", "azure.cn", "chinacloudapi.cn", "azure.us", // cloud-literal: every cloud's zone, to tell Azure hosts from floci-az's
            "usgovcloudapi.net"); // cloud-literal: every cloud's zone, to tell Azure hosts from floci-az's

    private ConnectionStringParts() {
    }

    /**
     * Splits {@code key=value;key=value} into its pairs, as the Azure SDKs do: keys match case-insensitively, a
     * value keeps everything after the first {@code '='} (account keys and SAS tokens contain more), empty
     * segments are skipped and a repeated key keeps its last value.
     *
     * @throws IllegalArgumentException when a segment is not a {@code key=value} pair
     */
    static Map<String, String> parse(String connectionString) {
        Map<String, String> pairs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String segment : connectionString.split(";")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("'" + trimmed + "' is not a key=value pair");
            }
            pairs.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
        }
        return pairs;
    }

    /**
     * The resource name in an ARM id of the form
     * {@code /subscriptions/{id}/resourceGroups/{group}/providers/{namespace}/{type}/{name}}, a trailing slash
     * allowed, with namespace and type matched case-insensitively; {@code null} for any other id.
     */
    static String armResourceName(String resourceId, String namespace, String type) {
        String[] segments = Arrays.stream(resourceId.trim().split("/"))
                .filter(segment -> !segment.isBlank())
                .toArray(String[]::new);
        boolean matches = segments.length == 8
                && "subscriptions".equalsIgnoreCase(segments[0])
                && "resourceGroups".equalsIgnoreCase(segments[2])
                && "providers".equalsIgnoreCase(segments[4])
                && namespace.equalsIgnoreCase(segments[5])
                && type.equalsIgnoreCase(segments[6]);
        return matches ? segments[7] : null;
    }

    /** The value as an absolute {@code http} or {@code https} URL with a host, or {@code null}. */
    static URI httpUrl(String value) {
        try {
            URI url = new URI(value.trim());
            String scheme = url.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return http && host(url) != null ? url : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * The URL's host in lower case, as floci-az routes on it. A name {@link URI} does not accept as a host (a
     * Docker container name with an underscore) is taken from the authority.
     */
    static String host(URI url) {
        String host = url.getHost();
        if (host == null && url.getRawAuthority() != null) {
            String authority = url.getRawAuthority();
            authority = authority.substring(authority.lastIndexOf('@') + 1);
            int port = authority.lastIndexOf(':');
            host = port < 0 ? authority : authority.substring(0, port);
        }
        return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
    }

    /** The host's dot-separated labels, lower-cased. */
    static List<String> hostLabels(URI url) {
        return List.of(host(url).split("\\."));
    }

    /** Whether the host lies in one of Azure's DNS zones. */
    static boolean azureHost(URI url) {
        String host = host(url);
        return AZURE_ZONES.stream().anyMatch(zone -> host.endsWith("." + zone));
    }

    /** The URL's decoded, non-empty path segments: what floci-az's path-style routing reads. */
    static List<String> pathSegments(URI url) {
        String path = url.getPath();
        if (path == null) {
            return List.of();
        }
        return Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isEmpty())
                .toList();
    }
}
