package com.github.guoyyit.fibroutingjava.web;

import java.util.List;

/** JSON body for staging a route upsert. */
public record RouteUpsertRequest(
        String prefix,
        String source,
        Integer adminDistance,
        List<HopSpec> nextHops) {
}
