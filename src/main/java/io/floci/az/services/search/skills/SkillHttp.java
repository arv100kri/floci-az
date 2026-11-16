package io.floci.az.services.search.skills;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Outbound HTTP for the skills that call an endpoint: the Custom Web API and AML skills, the embedding model, and
 * Document Extraction's file downloads. Retries the statuses a skill documents as transient twice, as the indexer does.
 */
final class SkillHttp {

    private static final Logger LOG = Logger.getLogger(SkillHttp.class);
    private static final int RETRIES = 2;
    private static final long RETRY_DELAY_MS = 50;
    private static final int EXCERPT_LENGTH = 500;

    private volatile HttpClient client;

    /**
     * Outbound HTTP that builds its client on the first call, so a pipeline that never calls an endpoint starts no
     * thread. Its owner closes it.
     */
    SkillHttp() {
        this(null);
    }

    SkillHttp(HttpClient client) {
        this.client = client;
    }

    /**
     * Stops the HTTP client's threads. The selector thread of an HTTP client still in use holds the context class
     * loader it was started with, so a client the application never stops keeps that application's classes loaded.
     */
    void close() {
        HttpClient current = client;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                    client = current;
                }
            }
        }
        return current;
    }

    /**
     * Sends a request and reads the whole response.
     *
     * @param method  {@code GET}, {@code POST} or {@code PUT}
     * @param uri     the endpoint
     * @param headers request headers; a header the HTTP client does not let callers set is left out
     * @param body    the request body, or null for none
     * @param timeout how long one attempt may take
     * @param retried the statuses retried twice before the response is returned
     * @throws java.net.http.HttpTimeoutException when an attempt times out
     * @throws IOException                        when the endpoint cannot be reached
     */
    Reply send(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout, Set<Integer> retried)
            throws IOException, InterruptedException {
        HttpRequest request = build(method, uri, headers, body, timeout);
        Reply reply = null;
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            if (attempt > 0) {
                Thread.sleep(RETRY_DELAY_MS * attempt);
            }
            HttpResponse<byte[]> response = client().send(request, HttpResponse.BodyHandlers.ofByteArray());
            reply = new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                response.body() == null ? new byte[0] : response.body());
            if (!retried.contains(reply.status())) {
                return reply;
            }
            LOG.debugv("Skill endpoint {0} answered {1}; attempt {2} of {3}", uri.getHost(), reply.status(),
                attempt + 1, RETRIES + 1);
        }
        return reply;
    }

    private static HttpRequest build(String method, URI uri, Map<String, String> headers, byte[] body,
                                     Duration timeout) {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).method(method, publisher);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            try {
                builder.setHeader(header.getKey(), header.getValue());
            } catch (IllegalArgumentException e) {
                LOG.debugv("The header {0} cannot be sent by the HTTP client and was left out: {1}", header.getKey(),
                    e.getMessage());
            }
        }
        return builder.build();
    }

    /**
     * A response.
     *
     * @param status      the status code
     * @param contentType the {@code Content-Type}, or null
     * @param body        the body
     */
    record Reply(int status, String contentType, byte[] body) {

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        /** Whether the response says it is JSON: {@code application/json} or a {@code +json} type. */
        boolean isJson() {
            if (contentType == null) {
                return false;
            }
            String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            return mediaType.equals("application/json") || mediaType.endsWith("+json");
        }

        /** The start of the body as text, for error details. */
        String excerpt() {
            String text = new String(body, StandardCharsets.UTF_8);
            return text.length() <= EXCERPT_LENGTH ? text : text.substring(0, EXCERPT_LENGTH) + "...";
        }
    }
}
