package com.github.guoyyit.fibroutingjava.web.dto;

/** 聚合结果：计算出的汇总前缀与新版本号。 */
public record AggregateResponse(String aggregatePrefix, long version, int routeCount) {
}
