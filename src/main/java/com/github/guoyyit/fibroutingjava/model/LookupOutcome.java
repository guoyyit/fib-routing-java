package com.github.guoyyit.fibroutingjava.model;

/** 查找结果类型：命中显式路由 / 命中默认路由 / 不可达。 */
public enum LookupOutcome {
    MATCHED,
    DEFAULT_ROUTE,
    UNREACHABLE
}
