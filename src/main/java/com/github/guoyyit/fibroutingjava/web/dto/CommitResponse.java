package com.github.guoyyit.fibroutingjava.web.dto;

/** 提交结果：新版本号与当前路由条数。 */
public record CommitResponse(long version, int routeCount, boolean committed) {
}
