package io.floci.az.core.tls;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Callers keep their own address behind the TLS proxy. With {@code floci-az.tls.enabled}, a search service under
 * {@code strict-network} whose one IP rule admits 127.0.0.2 answers a caller at 127.0.0.2 and refuses one at 127.0.0.1,
 * over HTTP and HTTPS alike and all through the one public port: the internal listeners take the caller's address from
 * the PROXY protocol header the proxy writes. Without it, every caller would be seen at the proxy's own address,
 * 127.0.0.1.
 */
@QuarkusTest
@TestProfile(TlsProxyClientAddressTest.TlsWithStrictNetwork.class)
class TlsProxyClientAddressTest {

    public static class TlsWithStrictNetwork implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-network", "true");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(TlsOn.class));
        }
    }

    /**
     * Turns TLS on with system properties, as TlsConfigSource reads its settings from system properties and the
     * environment only. They are restored when the application of this profile stops, so no other test runs with TLS.
     */
    public static class TlsOn implements QuarkusTestResourceLifecycleManager {

        private final Map<String, String> previous = new HashMap<>();

        @Override
        public Map<String, String> start() {
            set("floci-az.tls.enabled", "true");
            set("floci-az.tls.https-port", "0");
            set("floci-az.port", String.valueOf(freePort()));
            set("floci-az.storage.persistent-path", "target/tls-proxy-client-address");
            return Map.of();
        }

        @Override
        public void stop() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }

        private void set(String key, String value) {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final String SERVICE =
        "/subscriptions/sub-tls-proxy/resourceGroups/rg-tls-proxy/providers/Microsoft.Search/searchServices/tls-proxy";
    private static final String ARM_API = "?api-version=2026-11-01";
    private static final String LIST_INDEXES = "/tls-proxy-search/indexes?api-version=2024-07-01";

    private static final InetAddress ADMITTED = InetAddress.ofLiteral("127.0.0.2");
    private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

    @Inject
    EmulatorConfig config;

    @Test
    void theSearchFirewallSeesEachCallersOwnAddressOverHttpAndHttps() throws Exception {
        assumeTrue(isLocal(ADMITTED), "127.0.0.2 is not a local address on this host");
        int port = config.port();
        given().port(port).contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},"
                + "\"properties\":{\"networkRuleSet\":{\"ipRules\":[{\"value\":\"127.0.0.2\"}]}}}")
            .when().put(SERVICE + ARM_API)
            .then().statusCode(201);
        String apiKey = given().port(port).when().post(SERVICE + "/listAdminKeys" + ARM_API)
            .then().statusCode(200).extract().path("primaryKey");
        SSLContext tls = trusting(given().port(port).when().get("/_floci/tls-cert")
            .then().statusCode(200).extract().asString());

        assertEquals(200, listIndexes(ADMITTED, null, apiKey));
        assertEquals(200, listIndexes(ADMITTED, tls, apiKey));
        assertEquals(403, listIndexes(LOOPBACK, null, apiKey));
        assertEquals(403, listIndexes(LOOPBACK, tls, apiKey));
    }

    /**
     * The status of a request for the service's indexes that a client at {@code client} sends to the public port: in
     * plain HTTP, or over {@code tls} when it is given. The request goes out in one write, as some Windows hosts hold
     * back a first byte sent alone from 127.0.0.2.
     */
    private int listIndexes(InetAddress client, SSLContext tls, String apiKey) throws IOException {
        int port = config.port();
        try (Socket plain = new Socket(LOOPBACK, port, client, 0);
             Socket socket = tls == null ? plain : overTls(tls, plain, port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + LIST_INDEXES + " HTTP/1.1\r\nHost: localhost:" + port + "\r\napi-key: " + apiKey
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    /** {@code plain} after a TLS handshake that verifies the emulator's certificate names {@code localhost}. */
    private static Socket overTls(SSLContext tls, Socket plain, int port) throws IOException {
        SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(plain, "localhost", port, true);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    /** A TLS context that trusts the emulator's CA, as served at {@code /_floci/tls-cert}, and nothing else. */
    private static SSLContext trusting(String caPem) throws GeneralSecurityException, IOException {
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        anchors.load(null, null);
        anchors.setCertificateEntry("floci-az", CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.US_ASCII))));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(anchors);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    /** Whether a socket can be bound to {@code address}: Linux and Windows route all of 127.0.0.0/8, macOS only .1. */
    private static boolean isLocal(InetAddress address) {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(address, 0));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
