package com.github.guoyyit.fibroutingjava.trie;

import com.github.guoyyit.fibroutingjava.model.IpVersion;
import java.util.List;

/**
 * 面向最长前缀匹配的二进制前缀树。
 * 每个前缀节点可挂载一个版本内的负载（按管理距离排序的路由候选列表）。
 */
public interface PrefixTrie<V> {

    /** 插入或替换某个前缀上的负载。 */
    void put(byte[] prefixBytes, int prefixLength, V value);

    /** 删除某个前缀上的负载；不存在时返回 false。 */
    boolean remove(byte[] prefixBytes, int prefixLength);

    /** 返回目的地址命中的所有前缀负载，按前缀长度从长到短排列。 */
    List<V> longestPrefixMatch(byte[] destination);

    /** 是否包含指定前缀节点（含负载）。 */
    boolean contains(byte[] prefixBytes, int prefixLength);

    /** 当前已挂载负载的前缀数量。 */
    int size();

    IpVersion version();
}
