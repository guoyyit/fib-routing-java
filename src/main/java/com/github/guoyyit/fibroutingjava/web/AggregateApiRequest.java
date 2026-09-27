package com.github.guoyyit.fibroutingjava.web;

import java.util.List;

/** JSON body for a route aggregation request. */
public record AggregateApiRequest(
        List<String> components,
        String source,
        Integer adminDistance,
        List<HopSpec> nextHops) {
}
