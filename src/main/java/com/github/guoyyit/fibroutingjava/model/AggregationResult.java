package com.github.guoyyit.fibroutingjava.model;

/** Result of an accepted aggregation request. */
public record AggregationResult(IpPrefix aggregatePrefix, int componentCount, long version) {
}
