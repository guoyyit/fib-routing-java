package com.github.guoyyit.fibroutingjava.model;

/** Distinguishable rejection reasons for illegal route changes. */
public enum RouteErrorCode {
    DUPLICATE_PREFIX,
    DUPLICATE_NEXT_HOP_ID,
    INVALID_WEIGHT,
    EMPTY_NEXT_HOPS,
    INVALID_ADMIN_DISTANCE,
    PREFIX_FAMILY_CONFLICT,
    LOOP_DETECTED,
    OVERLAP_DETECTED,
    SHADOW_DETECTED,
    DUPLICATE_COMPONENT,
    PREFIX_NOT_FOUND,
    NO_ACTIVE_TRANSACTION,
    TABLE_LIMIT_EXCEEDED,
    MALFORMED_PREFIX
}
