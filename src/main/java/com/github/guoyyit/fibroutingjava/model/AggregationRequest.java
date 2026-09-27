package com.github.guoyyit.fibroutingjava.model;

import java.util.List;

/** Request to build and install a route that summarizes component prefixes. */
public record AggregationRequest(
        List<String> componentPrefixes,
        List<NextHop> nextHops,
        String source,
        int adminDistance) {
}
