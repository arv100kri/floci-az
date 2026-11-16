package io.floci.az.services.search.network;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An IPv4 or IPv6 address range in CIDR notation, such as {@code 10.0.1.0/24} or {@code fd00:db8::/64}; a bare address
 * is the range of that one address. Used for the IP rules of a search service's firewall and for the address prefixes
 * of the subnets that host its private endpoints.
 *
 * <p>Only address literals are read, never host names, so parsing never queries DNS. IPv4 takes the dotted-quad form
 * the search firewall documents; an IPv4-mapped IPv6 address ({@code ::ffff:10.0.1.5}) counts as its IPv4 address.
 */
public final class IpPrefix {

    private static final Pattern DOTTED_QUAD =
        Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    private static final Pattern PREFIX_LENGTH = Pattern.compile("\\d{1,3}");

    private final byte[] network;
    private final int length;
    private final String text;

    private IpPrefix(byte[] network, int length, String text) {
        this.network = network;
        this.length = length;
        this.text = text;
    }

    /** The range {@code text} names: an address, or an address and a prefix length; empty when it is neither. */
    public static Optional<IpPrefix> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        int slash = trimmed.indexOf('/');
        Optional<InetAddress> address = address(slash < 0 ? trimmed : trimmed.substring(0, slash));
        if (address.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = address.get().getAddress();
        int bits = bytes.length * 8;
        int length = bits;
        if (slash >= 0) {
            String suffix = trimmed.substring(slash + 1);
            if (!PREFIX_LENGTH.matcher(suffix).matches()) {
                return Optional.empty();
            }
            length = Integer.parseInt(suffix);
            if (length > bits) {
                return Optional.empty();
            }
        }
        return Optional.of(new IpPrefix(bytes, length, trimmed));
    }

    /**
     * The address {@code text} spells: a dotted-quad IPv4 address or an IPv6 address (in brackets or not, with or
     * without a zone), an IPv4-mapped IPv6 address being its IPv4 address. Empty for anything else, host names
     * included.
     */
    public static Optional<InetAddress> address(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (trimmed.indexOf(':') < 0 && !DOTTED_QUAD.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(trimmed));
        } catch (IllegalArgumentException expected) {
            // Not an address literal: callers treat such text as naming no address at all.
            return Optional.empty();
        }
    }

    /** Whether {@code address} falls in this range; an IPv4 address is never in an IPv6 range, nor the reverse. */
    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int whole = length / 8;
        for (int i = 0; i < whole; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int rest = length % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (candidate[whole] & mask) == (network[whole] & mask);
    }

    /**
     * Whether {@code address} is in this range and is not one of the addresses Azure reserves in a subnet that no
     * client ever has: the network address, the first address (the default gateway) and, for IPv4, the broadcast
     * address. Docker hands a network's first address to its gateway too, so a request the host sends into a Docker
     * network created over the subnet's prefix is never taken for a client of the subnet. A range of one or two
     * addresses reserves none.
     */
    public boolean containsHost(InetAddress address) {
        if (!contains(address)) {
            return false;
        }
        int hostBits = network.length * 8 - length;
        if (hostBits < 2) {
            return true;
        }
        BigInteger host = new BigInteger(1, address.getAddress()).and(BigInteger.ONE.shiftLeft(hostBits)
            .subtract(BigInteger.ONE));
        boolean broadcast = network.length == 4 && host.bitCount() == hostBits;
        return host.compareTo(BigInteger.ONE) > 0 && !broadcast;
    }

    @Override
    public String toString() {
        return text;
    }
}
