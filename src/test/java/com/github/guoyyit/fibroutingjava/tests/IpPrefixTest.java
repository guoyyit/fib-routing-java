package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.IpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpPrefixTest {

    @Test
    void parsesIpv4AndMasksHostBits() {
        IpPrefix p = IpPrefix.parse("10.1.2.3/8");
        assertEquals(IpVersion.V4, p.version());
        assertEquals(8, p.prefixLength());
        assertEquals("10.0.0.0/8", p.toString());
    }

    @Test
    void parsesIpv6AndMasksHostBits() {
        IpPrefix p = IpPrefix.parse("2001:db8:1::1/32");
        assertEquals(IpVersion.V6, p.version());
        assertEquals(32, p.prefixLength());
        assertEquals("2001:db8:0:0:0:0:0:0/32", p.toString());
    }

    @Test
    void boundaryAddressContainment() {
        IpPrefix slash24 = IpPrefix.parse("192.168.1.0/24");
        assertTrue(slash24.contains(IpPrefix.parseDestination("192.168.1.0")));
        assertTrue(slash24.contains(IpPrefix.parseDestination("192.168.1.255")));
        assertFalse(slash24.contains(IpPrefix.parseDestination("192.168.0.255")));
        assertFalse(slash24.contains(IpPrefix.parseDestination("192.168.2.0")));

        IpPrefix slash1 = IpPrefix.parse("0.0.0.0/1");
        assertTrue(slash1.contains(IpPrefix.parseDestination("127.255.255.255")));
        assertFalse(slash1.contains(IpPrefix.parseDestination("128.0.0.0")));

        IpPrefix slash31 = IpPrefix.parse("10.0.0.0/31");
        assertTrue(slash31.contains(IpPrefix.parseDestination("10.0.0.1")));
        assertFalse(slash31.contains(IpPrefix.parseDestination("10.0.0.2")));
    }

    @Test
    void ipv6OddBitBoundaries() {
        IpPrefix p = IpPrefix.parse("2001:db8::/33");
        // 前 32 位为 2001:0db8，第 33 位（第三个 hextet 最高位）须为 0
        assertTrue(p.contains(IpPrefix.parseDestination("2001:db8:7fff:ffff:ffff:ffff:ffff:ffff")));
        assertFalse(p.contains(IpPrefix.parseDestination("2001:db8:8000::")));
        assertTrue(p.contains(IpPrefix.parseDestination("2001:db8::1")));
    }

    @Test
    void hostRouteDefaultsToFullLength() {
        assertEquals(32, IpPrefix.parse("1.2.3.4").prefixLength());
        assertEquals(128, IpPrefix.parse("::1").prefixLength());
    }

    @Test
    void rejectsInvalidPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.parse("2001:db8::/129"));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.parse("not-an-ip/8"));
        assertThrows(IllegalArgumentException.class,
                () -> IpPrefix.parse("::ffff:1.2.3.4/96")); // v4-mapped 被拒绝
    }

    @Test
    void prefixContainmentBothDirections() {
        assertTrue(IpPrefix.parse("10.0.0.0/8")
                .containsPrefix(IpPrefix.parse("10.1.0.0/16")));
        assertFalse(IpPrefix.parse("10.1.0.0/16")
                .containsPrefix(IpPrefix.parse("10.0.0.0/8")));
        assertFalse(IpPrefix.parse("10.0.0.0/8")
                .containsPrefix(IpPrefix.parse("11.0.0.0/8")));
    }
}
