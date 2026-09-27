package com.github.guoyyit.fibroutingjava.model;

/**
 * 一次查找的完整结果。不可达时 selectedNextHop 为 null。
 * 结果中携带产生它的转发表版本号，便于验证版本一致性。
 */
public record LookupResult(LookupOutcome outcome,
                           IpPrefix matchedPrefix,
                           Integer adminDistance,
                           NextHop selectedNextHop,
                           String flowKey,
                           long version) {
}
