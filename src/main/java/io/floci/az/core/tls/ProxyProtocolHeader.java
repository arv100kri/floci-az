package io.floci.az.core.tls;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.SocketAddress;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * The PROXY protocol header {@link TlsProxyServer} opens every forwarded connection with, so that the internal listener
 * behind it (Quarkus, with {@code quarkus.http.proxy.use-proxy-protocol}) reports the client's own address as a
 * request's remote address instead of the proxy's loopback one.
 *
 * <p>Version 2, the binary form of HAProxy's "The PROXY protocol" specification (section 2.2): the twelve-byte
 * signature, the version and command, the address family and transport, the length of the address block, then the
 * source and destination addresses and ports in network byte order. Both addresses must be of one family, so an IPv4
 * address paired with an IPv6 one is written in its IPv4-mapped IPv6 form.
 */
final class ProxyProtocolHeader {

    private static final Logger LOG = Logger.getLogger(ProxyProtocolHeader.class);

    private static final byte[] SIGNATURE = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    /** Version 2 in the high nibble, the command in the low one. */
    private static final byte VERSION_2_LOCAL = 0x20;
    private static final byte VERSION_2_PROXY = 0x21;

    /** The address family in the high nibble, the transport protocol in the low one. */
    private static final byte UNSPECIFIED = 0x00;
    private static final byte TCP_OVER_IPV4 = 0x11;
    private static final byte TCP_OVER_IPV6 = 0x21;

    /** The signature, the two bytes above and the two-byte length of the address block. */
    private static final int FIXED_LENGTH = SIGNATURE.length + 4;

    private static final int IPV4_LENGTH = 4;
    private static final int IPV6_LENGTH = 16;

    private ProxyProtocolHeader() {
    }

    /**
     * The header for a TCP connection from {@code source} to {@code destination}: a PROXY command naming both, or a
     * LOCAL command, which has the receiver keep the connection's own addresses, when either is not an IP address.
     */
    static Buffer of(SocketAddress source, SocketAddress destination) {
        Optional<InetAddress> from = ipAddress(source);
        Optional<InetAddress> to = ipAddress(destination);
        if (from.isEmpty() || to.isEmpty()) {
            return local();
        }
        byte[] sourceAddress = from.get().getAddress();
        byte[] destinationAddress = to.get().getAddress();
        if (sourceAddress.length != destinationAddress.length) {
            sourceAddress = ipv6(sourceAddress);
            destinationAddress = ipv6(destinationAddress);
        }
        int addressBlock = 2 * sourceAddress.length + 2 * Short.BYTES;
        ByteBuffer header = ByteBuffer.allocate(FIXED_LENGTH + addressBlock)
            .put(SIGNATURE)
            .put(VERSION_2_PROXY)
            .put(sourceAddress.length == IPV4_LENGTH ? TCP_OVER_IPV4 : TCP_OVER_IPV6)
            .putShort((short) addressBlock)
            .put(sourceAddress)
            .put(destinationAddress)
            .putShort((short) source.port())
            .putShort((short) destination.port());
        return Buffer.buffer(header.array());
    }

    /** A LOCAL command, with no address block: the receiver keeps the addresses of the connection itself. */
    static Buffer local() {
        return Buffer.buffer(ByteBuffer.allocate(FIXED_LENGTH)
            .put(SIGNATURE)
            .put(VERSION_2_LOCAL)
            .put(UNSPECIFIED)
            .putShort((short) 0)
            .array());
    }

    /** The IP address {@code address} holds; empty for a domain socket or a host that is not an address literal. */
    private static Optional<InetAddress> ipAddress(SocketAddress address) {
        if (address == null || !address.isInetSocket() || address.hostAddress() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(address.hostAddress()));
        } catch (IllegalArgumentException e) {
            LOG.debugv("TLS proxy: {0} is not an IP address; the connection is forwarded with a LOCAL header",
                address.hostAddress());
            return Optional.empty();
        }
    }

    /** {@code address} in 16 bytes: an IPv4 address becomes its IPv4-mapped IPv6 address, {@code ::ffff:a.b.c.d}. */
    private static byte[] ipv6(byte[] address) {
        if (address.length == IPV6_LENGTH) {
            return address;
        }
        byte[] mapped = new byte[IPV6_LENGTH];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        System.arraycopy(address, 0, mapped, IPV6_LENGTH - IPV4_LENGTH, IPV4_LENGTH);
        return mapped;
    }
}
