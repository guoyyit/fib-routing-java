package com.github.guoyyit.fibroutingjava.web.dto;

import java.util.List;

/**
 * 一次原子提交的批次：upsert 新增/替换，delete 删除（"cidr" 或 "cidr@adminDistance"）；
 * autocommit 为 false 时仅暂存（不生效、不持久化）。
 */
public record BatchUpdateRequest(List<RouteDto> upsert, List<String> delete, Boolean autocommit) {
}
