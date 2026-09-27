package com.github.guoyyit.fibroutingjava.fib;

/** 查找在完成前被外部取消时抛出；调用方绝不会拿到部分结果。 */
public class LookupCancelledException extends RuntimeException {
    public LookupCancelledException(String message) {
        super(message);
    }
}
