package com.myharness.codex.entity.vo;

import java.util.List;

public record ModelSelectableVersionVO(Long configurationId,Long versionId,Long versionNo,String configurationCode,
    String name,String providerName,String modelId,List<String> inputModalities,String configDigest) {}
