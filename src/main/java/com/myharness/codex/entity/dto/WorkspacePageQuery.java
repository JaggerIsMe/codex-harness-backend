package com.myharness.codex.entity.dto;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;

/** Shared bounds for the Project and Conversation lists in a Workspace. */
public record WorkspacePageQuery(int page, int size, String keyword) {
    public WorkspacePageQuery {
        if (page < 1 || page > 100000 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "页码须为 1–100000，每页数量须为 1–100");
        if (keyword != null && keyword.length() > 200)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "搜索关键词不能超过 200 个字符");
        keyword = keyword == null ? "" : keyword.trim();
    }

    public long offset() {
        return (page - 1L) * size;
    }
}
