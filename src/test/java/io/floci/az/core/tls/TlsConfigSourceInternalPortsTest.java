package io.floci.az.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The internal listeners behind the TLS proxy: free ports rather than fixed ones, so that several TLS-enabled emulators
 * can run on one machine, unless set explicitly; and the PROXY protocol, which only TLS turns on.
 */
class TlsConfigSourceInternalPortsTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        System.setProperty("floci-az.tls.enabled", "true");
        System.setProperty("floci-az.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-az.tls.enabled");
        System.clearProperty("floci-az.storage.persistent-path");
        System.clearProperty(TlsConfigSource.HTTP_PORT_PROPERTY);
        System.clearProperty(TlsConfigSource.HTTPS_PORT_PROPERTY);
    }

    @Test
    void theInternalListenersGetTwoFreePortsInEveryLaunchModeAndReadTheProxyProtocol() {
        TlsConfigSource source = new TlsConfigSource();

        int http = Integer.parseInt(source.getValue("quarkus.http.port"));
        int https = Integer.parseInt(source.getValue("quarkus.http.ssl-port"));
        assertTrue(http > 0 && https > 0, http + ", " + https);
        assertNotEquals(http, https);
        assertEquals(String.valueOf(http), source.getValue("quarkus.http.test-port"));
        assertEquals(String.valueOf(https), source.getValue("quarkus.http.test-ssl-port"));
        assertEquals("true", source.getValue("quarkus.http.proxy.use-proxy-protocol"));
    }

    @Test
    void everyInstanceNamesTheSamePortsForQuarkusBuildsItsConfigurationMoreThanOnce() {
        TlsConfigSource first = new TlsConfigSource();
        TlsConfigSource second = new TlsConfigSource();

        assertEquals(first.getValue("quarkus.http.port"), second.getValue("quarkus.http.port"));
        assertEquals(first.getValue("quarkus.http.ssl-port"), second.getValue("quarkus.http.ssl-port"));
    }

    @Test
    void portsSetExplicitlyAreKept() {
        System.setProperty(TlsConfigSource.HTTP_PORT_PROPERTY, "45701");
        System.setProperty(TlsConfigSource.HTTPS_PORT_PROPERTY, "45702");

        TlsConfigSource source = new TlsConfigSource();

        assertEquals("45701", source.getValue("quarkus.http.port"));
        assertEquals("45702", source.getValue("quarkus.http.ssl-port"));
    }

    @Test
    void aPortSetForOneListenerLeavesTheOtherAFreePortOfItsOwn() {
        // The free port the HTTP listener would get otherwise: the HTTPS one, set to it, must not share it.
        String https = String.valueOf(TlsConfigSource.internalPorts().http());
        System.setProperty(TlsConfigSource.HTTPS_PORT_PROPERTY, https);

        TlsConfigSource source = new TlsConfigSource();

        assertEquals(https, source.getValue("quarkus.http.ssl-port"));
        assertNotEquals(https, source.getValue("quarkus.http.port"));
    }

    @Test
    void withTlsOffTheSourceSetsNothing() {
        System.setProperty("floci-az.tls.enabled", "false");

        assertTrue(new TlsConfigSource().getPropertyNames().isEmpty());
    }
}
