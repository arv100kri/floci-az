package io.floci.az.core.tls;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The header {@link TlsProxyServer} writes, read back by Netty's {@link HAProxyMessageDecoder}: the decoder Vert.x, and
 * so Quarkus's internal listeners, read it with.
 */
class ProxyProtocolHeaderTest {

    private static final String REQUEST = "GET / HTTP/1.1\r\n";

    private final EmbeddedChannel channel = new EmbeddedChannel(new HAProxyMessageDecoder());

    @AfterEach
    void release() {
        channel.finishAndReleaseAll();
    }

    @Test
    void anIpv4ConnectionCarriesBothAddressesAndPortsAheadOfTheClientsBytes() {
        HAProxyMessage message = decode(ProxyProtocolHeader.of(SocketAddress.inetSocketAddress(50123, "127.0.0.2"),
            SocketAddress.inetSocketAddress(4577, "127.0.0.1")));

        assertEquals(HAProxyProtocolVersion.V2, message.protocolVersion());
        assertEquals(HAProxyCommand.PROXY, message.command());
        assertEquals(HAProxyProxiedProtocol.TCP4, message.proxiedProtocol());
        assertEquals("127.0.0.2", message.sourceAddress());
        assertEquals(50123, message.sourcePort());
        assertEquals("127.0.0.1", message.destinationAddress());
        assertEquals(4577, message.destinationPort());
        message.release();
        assertEquals(REQUEST, followingBytes());
    }

    @Test
    void anIpv6ConnectionIsTcp6AndAnIpv4AddressBesideAnIpv6OneIsMapped() {
        HAProxyMessage ipv6 = decode(ProxyProtocolHeader.of(SocketAddress.inetSocketAddress(65535, "2001:db8::7"),
            SocketAddress.inetSocketAddress(443, "2001:db8::1")));

        assertEquals(HAProxyProxiedProtocol.TCP6, ipv6.proxiedProtocol());
        assertEquals("2001:db8:0:0:0:0:0:7", ipv6.sourceAddress());
        assertEquals(65535, ipv6.sourcePort());
        assertEquals("2001:db8:0:0:0:0:0:1", ipv6.destinationAddress());
        ipv6.release();
        assertEquals(REQUEST, followingBytes());

        EmbeddedChannel mixed = new EmbeddedChannel(new HAProxyMessageDecoder());
        mixed.writeInbound(Unpooled.wrappedBuffer(ProxyProtocolHeader.of(
            SocketAddress.inetSocketAddress(50123, "127.0.0.2"), SocketAddress.inetSocketAddress(4577, "::1")).getBytes()));
        HAProxyMessage mapped = mixed.readInbound();
        assertEquals(HAProxyProxiedProtocol.TCP6, mapped.proxiedProtocol());
        assertEquals("0:0:0:0:0:ffff:7f00:2", mapped.sourceAddress());
        mapped.release();
        mixed.finishAndReleaseAll();
    }

    @Test
    void aConnectionWithoutIpAddressesIsLocalSoTheReceiverKeepsItsOwn() {
        HAProxyMessage message = decode(ProxyProtocolHeader.of(SocketAddress.domainSocketAddress("floci-az.sock"),
            SocketAddress.inetSocketAddress(4577, "127.0.0.1")));

        assertEquals(HAProxyCommand.LOCAL, message.command());
        assertEquals(HAProxyProxiedProtocol.UNKNOWN, message.proxiedProtocol());
        assertNull(message.sourceAddress());
        message.release();
        assertEquals(REQUEST, followingBytes());
    }

    /** Feeds {@code header}, followed by the first bytes of a request, to the decoder; returns what it decoded. */
    private HAProxyMessage decode(Buffer header) {
        channel.writeInbound(Unpooled.wrappedBuffer(header.appendString(REQUEST).getBytes()));
        return channel.readInbound();
    }

    /** The bytes the decoder passed on after the header. */
    private String followingBytes() {
        ByteBuf rest = channel.readInbound();
        try {
            return rest.toString(StandardCharsets.US_ASCII);
        } finally {
            rest.release();
        }
    }
}
