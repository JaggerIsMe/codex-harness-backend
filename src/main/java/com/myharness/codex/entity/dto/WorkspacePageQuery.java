package com.myharness.codex.entity.dto;

/** Shared bounds for the Project and Conversation lists in a Workspace. */
public record WorkspacePageQuery(int page, int size, String keyword) {
    public WorkspacePageQuery {
        keyword = new PageQueryDTO(page, size, keyword).keyword();
    }

    public long offset() {
        return (page - 1L) * size;
    }
}
