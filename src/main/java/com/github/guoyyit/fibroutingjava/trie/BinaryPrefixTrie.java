package com.github.guoyyit.fibroutingjava.trie;

import com.github.guoyyit.fibroutingjava.model.IpVersion;
import java.util.ArrayList;
import java.util.List;

/**
 * 二进制前缀树：IPv4/IPv6 共用，按比特位下沉。
 * LPM 查找时沿路径记录所有带负载的节点，天然得到最长优先序列。
 *
 * <p>本类仅在“构造新版本快照”期间被单线程写，发布后只读，
 * 因此不加锁；查询侧遍历的是已发布树，享受不可变可见性。</p>
 */
public final class BinaryPrefixTrie<V> implements PrefixTrie<V> {

    private final IpVersion version;
    private final PrefixTrieNode<V> root = new PrefixTrieNode<>();
    private int size;

    public BinaryPrefixTrie(IpVersion version) {
        this.version = version;
    }

    @Override
    public void put(byte[] prefixBytes, int prefixLength, V value) {
        if (prefixBytes == null || value == null) {
            throw new IllegalArgumentException("prefixBytes/value must not be null");
        }
        PrefixTrieNode<V> node = root;
        for (int bit = 0; bit < prefixLength; bit++) {
            node = advanceOrCreate(node, prefixBytes, bit);
        }
        if (!node.hasValue) {
            size++;
        }
        node.value = value;
        node.hasValue = true;
    }

    @Override
    public boolean remove(byte[] prefixBytes, int prefixLength) {
        PrefixTrieNode<V> node = root;
        for (int bit = 0; bit < prefixLength; bit++) {
            node = advance(node, prefixBytes, bit);
            if (node == null) {
                return false;
            }
        }
        if (!node.hasValue) {
            return false;
        }
        node.hasValue = false;
        node.value = null;
        size--;
        // 不主动收缩树枝：版本快照随 GC 回收，空枝不影响正确性与查找复杂度（深度有界）
        return true;
    }

    @Override
    public List<V> longestPrefixMatch(byte[] destination) {
        List<V> hits = new ArrayList<>();
        PrefixTrieNode<V> node = root;
        if (node.hasValue) {
            hits.add(node.value);
        }
        int maxBits = Math.min(destination.length * 8, version.maxPrefixLength());
        for (int bit = 0; bit < maxBits; bit++) {
            node = advance(node, destination, bit);
            if (node == null) {
                break;
            }
            if (node.hasValue) {
                hits.add(node.value);
            }
        }
        // 根（0 位）最先加入，之后位长递增 => 天然按前缀长度升序，反转为最长优先
        java.util.Collections.reverse(hits);
        return hits;
    }

    @Override
    public boolean contains(byte[] prefixBytes, int prefixLength) {
        PrefixTrieNode<V> node = root;
        for (int bit = 0; bit < prefixLength; bit++) {
            node = advance(node, prefixBytes, bit);
            if (node == null) {
                return false;
            }
        }
        return node.hasValue;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public IpVersion version() {
        return version;
    }

    private static boolean bitAt(byte[] bytes, int bitIndex) {
        return ((bytes[bitIndex >> 3] >> (7 - (bitIndex & 7))) & 1) == 1;
    }

    private PrefixTrieNode<V> advanceOrCreate(PrefixTrieNode<V> node, byte[] bytes, int bit) {
        if (bitAt(bytes, bit)) {
            if (node.one == null) {
                node.one = new PrefixTrieNode<>();
            }
            return node.one;
        }
        if (node.zero == null) {
            node.zero = new PrefixTrieNode<>();
        }
        return node.zero;
    }

    private PrefixTrieNode<V> advance(PrefixTrieNode<V> node, byte[] bytes, int bit) {
        return bitAt(bytes, bit) ? node.one : node.zero;
    }
}
