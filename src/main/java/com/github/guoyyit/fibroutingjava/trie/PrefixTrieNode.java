package com.github.guoyyit.fibroutingjava.trie;

/** 前缀树内部节点骨架。 */
final class PrefixTrieNode<V> {
    PrefixTrieNode<V> zero;
    PrefixTrieNode<V> one;
    V value;
    boolean hasValue;

    PrefixTrieNode() {
    }
}
