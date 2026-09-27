package com.github.guoyyit.fibroutingjava.web.dto;

/** 查找结果传输对象。 */
public record LookupResponse(String outcome,
                             String destination,
                             String matchedPrefix,
                             Integer adminDistance,
                             String nextHopId,
                             String nextHopAddress,
                             String flowKey,
                             long version) {
}
