package io.floci.az.services.search.network;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpPrefixTest {

    private static InetAddress address(String text) {
        return IpPrefix.address(text).orElseThrow();
    }

    private static IpPrefix prefix(String text) {
        return IpPrefix.parse(text).orElseThrow();
    }

    @Test
    void anIpv4RangeContainsTheAddressesOfItsPrefix() {
        IpPrefix range = prefix("10.0.1.0/24");
        assertTrue(range.contains(address("10.0.1.0")));
        assertTrue(range.contains(address("10.0.1.255")));
        assertFalse(range.contains(address("10.0.2.1")));
        assertFalse(range.contains(address("11.0.1.1")));
    }

    @Test
    void prefixLengthsThatAreNotWholeBytesCompareTheirLeadingBits() {
        IpPrefix range = prefix("123.4.6.0/18");
        assertTrue(range.contains(address("123.4.0.1")));
        assertTrue(range.contains(address("123.4.63.255")));
        assertFalse(range.contains(address("123.4.64.0")));
        assertTrue(prefix("0.0.0.0/0").contains(address("203.0.113.9")));
    }

    @Test
    void aBareAddressIsARangeOfOneAddress() {
        IpPrefix single = prefix("123.4.5.6");
        assertTrue(single.contains(address("123.4.5.6")));
        assertFalse(single.contains(address("123.4.5.7")));
        assertEquals("123.4.5.6", single.toString());
    }

    @Test
    void ipv6RangesMatchIpv6AddressesOnly() {
        IpPrefix range = prefix("fd00:db8:1::/64");
        assertTrue(range.contains(address("fd00:db8:1::5")));
        assertTrue(range.contains(address("[fd00:db8:1:0:ffff::1]")));
        assertFalse(range.contains(address("fd00:db8:2::5")));
        assertFalse(range.contains(address("10.0.1.5")));
        assertFalse(prefix("10.0.0.0/8").contains(address("fd00:db8:1::5")));
        assertTrue(prefix("::1/128").contains(address("0:0:0:0:0:0:0:1")));
    }

    @Test
    void anIpv4MappedIpv6AddressIsItsIpv4Address() {
        assertTrue(prefix("10.0.1.0/24").contains(address("::ffff:10.0.1.5")));
    }

    @Test
    void theAddressesAzureReservesInASubnetAreNoClientsHosts() {
        IpPrefix subnet = prefix("10.0.1.0/24");
        assertFalse(subnet.containsHost(address("10.0.1.0")), "the network address");
        assertFalse(subnet.containsHost(address("10.0.1.1")), "the default gateway, Docker's network gateway too");
        assertFalse(subnet.containsHost(address("10.0.1.255")), "the broadcast address");
        assertTrue(subnet.containsHost(address("10.0.1.2")));
        assertTrue(subnet.containsHost(address("10.0.1.254")));
        assertFalse(subnet.containsHost(address("10.0.2.5")));

        IpPrefix ipv6 = prefix("fd00:db8:1::/64");
        assertFalse(ipv6.containsHost(address("fd00:db8:1::")));
        assertFalse(ipv6.containsHost(address("fd00:db8:1::1")));
        assertTrue(ipv6.containsHost(address("fd00:db8:1:0:ffff:ffff:ffff:ffff")), "IPv6 has no broadcast");

        assertTrue(prefix("127.0.0.1/32").containsHost(address("127.0.0.1")), "a single address reserves none");
        assertTrue(prefix("10.0.0.0/31").containsHost(address("10.0.0.0")));
        assertTrue(prefix("::1/128").containsHost(address("::1")));
    }

    @Test
    void onlyAddressLiteralsAreRead() {
        assertTrue(IpPrefix.address("search.example.com").isEmpty(), "a host name is never resolved");
        assertTrue(IpPrefix.address("abc").isEmpty());
        assertTrue(IpPrefix.address("10.1").isEmpty(), "IPv4 takes the dotted-quad form only");
        assertTrue(IpPrefix.address("256.1.1.1").isEmpty());
        assertTrue(IpPrefix.address(null).isEmpty());
        assertTrue(IpPrefix.address(" ").isEmpty());
        assertTrue(IpPrefix.parse("10.0.0.0/33").isEmpty());
        assertTrue(IpPrefix.parse("10.0.0.0/x").isEmpty());
        assertTrue(IpPrefix.parse("fd00::/129").isEmpty());
        assertTrue(IpPrefix.parse("example.com/24").isEmpty());
    }
}
