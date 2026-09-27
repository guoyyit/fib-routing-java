package com.github.guoyyit.fibroutingjava.web.dto;

/** 下一跳传输对象。 */
public record NextHopDto(String id, String address, Integer weight, Boolean enabled) {
}
