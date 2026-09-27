package com.github.guoyyit.fibroutingjava.fib;

/** 查找超过配置的单次查找预算时抛出；调用方绝不会拿到部分结果。 */
public class LookupTimeoutException extends RuntimeException {
    public LookupTimeoutException(String message) {
        super(message);
    }
}
