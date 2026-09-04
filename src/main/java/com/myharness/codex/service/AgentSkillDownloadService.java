package com.myharness.codex.service;

import com.myharness.codex.entity.vo.SkillFileVO;
import java.io.IOException;

public interface AgentSkillDownloadService {
    SkillFileVO download(Long versionId,String deviceCode,String authorization) throws IOException;
}
