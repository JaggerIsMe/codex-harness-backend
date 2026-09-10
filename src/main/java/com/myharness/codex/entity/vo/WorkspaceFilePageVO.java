package com.myharness.codex.entity.vo;
import java.util.List;
public record WorkspaceFilePageVO<T>(List<T> items,String nextCursor) {}
