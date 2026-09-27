package com.github.guoyyit.fibroutingjava.web;

/** JSON view of a lookup result. */
public record LookupResponse(
        String outcome,
        String destination,
        String matchedPrefix,
        String source,
        String nextHopId,
        String nextHopAddress,
        long version) {
}
