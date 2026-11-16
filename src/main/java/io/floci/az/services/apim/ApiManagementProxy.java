package io.floci.az.services.apim;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ApiManagementProxy {

    private volatile HttpClient httpClient;

    /**
     * Stops the HTTP client's threads. Its selector thread holds the context class loader it was started with, so a
     * client left running keeps the stopped application's classes loaded.
     */
    @PreDestroy
    void stop() {
        HttpClient client = httpClient;
        if (client != null) {
            client.shutdownNow();
        }
    }

    /**
     * The HTTP client, built on first use rather than in a constructor, which the bean's client proxy would run too.
     */
    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newHttpClient();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    public Response invoke(AzureRequest request, ProxyRequest proxyRequest) {
        if (proxyRequest.serviceUrl() != null) {
            return proxy(request, proxyRequest.serviceUrl(), proxyRequest.backendPath(),
                    proxyRequest.headers(), proxyRequest.queryParams());
        }
        return noBackend(proxyRequest);
    }

    // Emulator choice: what the real gateway answers when an API has neither a serviceUrl nor a
    // set-backend-service policy is not documented in any reference we can check, so the request fails
    // as a server-side configuration error rather than inventing a success. Use a return-response
    // policy to mock an API without a backend.
    private Response noBackend(ProxyRequest proxyRequest) {
        return ArmErrors.error(500, "BackendNotConfigured",
                "API '" + proxyRequest.apiId() + "' in service '" + proxyRequest.serviceName()
                        + "' has no backend: set properties.serviceUrl, add a set-backend-service policy,"
                        + " or answer with a return-response policy.");
    }

    private Response proxy(AzureRequest request, String serviceUrl, String suffix, Map<String, String> extraHeaders,
                           Map<String, String> extraQueryParams) {
        try {
            String target = serviceUrl.replaceAll("/+$", "") + "/" + trimSlashes(suffix)
                    + queryString(extraQueryParams);
            byte[] body = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                    .method(request.method(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            String contentType = firstHeader(request, "Content-Type");
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            extraHeaders.forEach(builder::header);
            HttpResponse<byte[]> response = httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder out = Response.status(response.statusCode()).entity(response.body());
            response.headers().firstValue("Content-Type").ifPresent(out::type);
            return out.build();
        } catch (Exception e) {
            return Response.status(502).entity(Map.of("error", Map.of(
                    "code", "BackendUnavailable",
                    "message", e.getMessage() == null ? "Backend unavailable" : e.getMessage()
            ))).build();
        }
    }

    private static String queryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        params.forEach((k, v) -> pairs.add(encode(k) + "=" + encode(v)));
        return "?" + String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstHeader(AzureRequest request, String name) {
        if (request.headers() == null) {
            return null;
        }
        return request.headers().getHeaderString(name);
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    public record ProxyRequest(String serviceName, String apiId, String gatewayPath, String backendPath,
                               String operationId, String serviceUrl, Map<String, String> headers,
                               Map<String, String> queryParams) {
    }
}
