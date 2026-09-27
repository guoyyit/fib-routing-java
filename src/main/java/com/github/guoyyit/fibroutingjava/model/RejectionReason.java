package com.github.guoyyit.fibroutingjava.model;

/** 非法路由变更的可区分拒绝原因。 */
public enum RejectionReason {
    INVALID_PREFIX,
    INVALID_ADDRESS,
    INVALID_WEIGHT,
    INVALID_DISTANCE,
    EMPTY_NEXTHOP_SET,
    DUPLICATE_NEXTHOP,
    DUPLICATE_ROUTE,
    TABLE_FULL,
    RESOLUTION_LOOP,
    AGGREGATE_MISMATCH,
    SHADOWED_BY_EXISTING,
    OVERLAP,
    PERSISTENCE_CORRUPT,
    UNKNOWN_PREFIX
}
