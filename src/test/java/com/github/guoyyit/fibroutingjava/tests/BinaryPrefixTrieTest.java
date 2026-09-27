package com.github.guoyyit.fibroutingjava.tests;

import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.IpVersion;
import com.github.guoyyit.fibroutingjava.trie.BinaryPrefixTrie;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryPrefixTrieTest {

    private byte[] v4(String cidrOrIp) {
        // 对纯地址 parseDestination 也可，这里统一用前缀解析（主机位会被掩码，测试路径时小心）
        return IpPrefix.parseDestination(cidrOrIp);
    }

    @Test
    void longestPrefixMatchReturnsLongestFirst() {
        BinaryPrefixTrie<String> trie = new BinaryPrefixTrie<>(IpVersion.V4);
        trie.put(IpPrefix.parse("0.0.0.0/0").addressBytes(), 0, "default");
        trie.put(IpPrefix.parse("10.0.0.0/8").addressBytes(), 8, "ten");
        trie.put(IpPrefix.parse("10.1.0.0/16").addressBytes(), 16, "ten-one");
        trie.put(IpPrefix.parse("10.1.2.0/24").addressBytes(), 24, "ten-one-two");

        List<String> hits = trie.longestPrefixMatch(v4("10.1.2.99"));
        assertEquals(List.of("ten-one-two", "ten-one", "ten", "default"), hits);

        assertEquals(List.of("ten-one", "ten", "default"),
                trie.longestPrefixMatch(v4("10.1.9.9")));
        assertEquals(List.of("default"), trie.longestPrefixMatch(v4("8.8.8.8")));
    }

    @Test
    void ipv6Lpm() {
        BinaryPrefixTrie<String> trie = new BinaryPrefixTrie<>(IpVersion.V6);
        trie.put(IpPrefix.parse("::/0").addressBytes(), 0, "v6default");
        trie.put(IpPrefix.parse("2001:db8::/32").addressBytes(), 32, "doc");
        trie.put(IpPrefix.parse("2001:db8:1::/48").addressBytes(), 48, "doc-1");

        List<String> hits = trie.longestPrefixMatch(IpPrefix.parseDestination("2001:db8:1::dead"));
        assertEquals(List.of("doc-1", "doc", "v6default"), hits);
        assertEquals(List.of("v6default"),
                trie.longestPrefixMatch(IpPrefix.parseDestination("2002::1")));
    }

    @Test
    void removeAndSize() {
        BinaryPrefixTrie<String> trie = new BinaryPrefixTrie<>(IpVersion.V4);
        trie.put(IpPrefix.parse("10.0.0.0/8").addressBytes(), 8, "a");
        trie.put(IpPrefix.parse("10.1.0.0/16").addressBytes(), 16, "b");
        assertEquals(2, trie.size());
        assertTrue(trie.remove(IpPrefix.parse("10.1.0.0/16").addressBytes(), 16));
        assertFalse(trie.remove(IpPrefix.parse("10.1.0.0/16").addressBytes(), 16));
        assertEquals(1, trie.size());
        assertEquals(List.of("a"), trie.longestPrefixMatch(v4("10.1.1.1")));
        assertTrue(trie.contains(IpPrefix.parse("10.0.0.0/8").addressBytes(), 8));
        assertFalse(trie.contains(IpPrefix.parse("10.1.0.0/16").addressBytes(), 16));
    }

    @Test
    void bitBoundaryPrefixes() {
        BinaryPrefixTrie<String> trie = new BinaryPrefixTrie<>(IpVersion.V4);
        trie.put(IpPrefix.parse("0.0.0.0/1").addressBytes(), 1, "low");
        trie.put(IpPrefix.parse("128.0.0.0/1").addressBytes(), 1, "high");
        assertEquals(List.of("low"), trie.longestPrefixMatch(v4("127.255.255.255")));
        assertEquals(List.of("high"), trie.longestPrefixMatch(v4("128.0.0.1")));
    }
}
