package com.github.guoyyit.fibroutingjava.web;

/** JSON shape of one next hop in an upsert request. */
public record HopSpec(String id, String address, Integer weight, Boolean enabled) {
}
