package com.github.guoyyit.fibroutingjava.web;

/** JSON body for a next-hop health change. */
public record HealthRequest(String hopId, boolean enabled) {
}
