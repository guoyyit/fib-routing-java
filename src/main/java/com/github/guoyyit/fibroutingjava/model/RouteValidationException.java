package com.github.guoyyit.fibroutingjava.model;

/** Thrown when a requested route change is illegal and must be rejected. */
public class RouteValidationException extends RuntimeException {

    private final RouteErrorCode errorCode;

    public RouteValidationException(RouteErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RouteErrorCode errorCode() {
        return errorCode;
    }
}
