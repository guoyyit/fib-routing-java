package com.github.guoyyit.fibroutingjava.web.dto;

import java.util.List;

/** 聚合请求：成员前缀 + 汇总路由参数，服务端计算最短覆盖前缀。 */
public record AggregateRequest(List<String> members, Integer adminDistance, List<NextHopDto> nextHops) {
}
