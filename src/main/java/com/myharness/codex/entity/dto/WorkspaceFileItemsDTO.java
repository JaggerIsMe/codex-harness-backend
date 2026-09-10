package com.myharness.codex.entity.dto;
import java.util.List;
public record WorkspaceFileItemsDTO(List<Item> items) {
    public record Item(String path,String entryType,String status,String code,String error) {}
}
