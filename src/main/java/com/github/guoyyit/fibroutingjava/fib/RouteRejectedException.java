package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.model.RejectionReason;

/** 非法路由变更被拒绝时抛出，携带可区分的原因码与可读说明。 */
public class RouteRejectedException extends RuntimeException {

    private final RejectionReason reason;

    public RouteRejectedException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
