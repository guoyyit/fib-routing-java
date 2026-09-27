package com.github.guoyyit.fibroutingjava.web.dto;

/** 统一错误响应：携带可区分的机器可读原因码。 */
public record ErrorResponse(String reason, String message) {
}
