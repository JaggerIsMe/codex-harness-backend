package com.myharness.codex.entity.vo;
import java.util.List;
public record PageVO<T>(List<T> items, long total, int page, int size) {}

