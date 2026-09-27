package com.github.guoyyit.fibroutingjava.model;

import java.util.Objects;

/**
 * 不可变的下一跳。
 * weight 为加权多路径权重（必须为正）；enabled 为管理面启用开关，
 * 运行期健康状态由 health 平面单独维护。
 */
public record NextHop(String id, String address, int weight, boolean enabled) {

    public NextHop {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(address, "address");
    }
}
