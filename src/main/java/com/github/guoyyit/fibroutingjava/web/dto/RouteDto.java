package com.github.guoyyit.fibroutingjava.web.dto;

import java.util.List;

/** 路由项传输对象：prefix 为 CIDR，adminDistance 缺省为 0。 */
public record RouteDto(String prefix, Integer adminDistance, List<NextHopDto> nextHops) {
}
