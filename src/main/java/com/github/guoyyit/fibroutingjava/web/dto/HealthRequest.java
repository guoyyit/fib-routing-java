package com.github.guoyyit.fibroutingjava.web.dto;

/** 下一跳健康状态变更请求。 */
public record HealthRequest(String nextHopId, boolean healthy) {
}
