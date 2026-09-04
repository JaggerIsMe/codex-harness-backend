package com.myharness.codex.service;

import com.myharness.codex.entity.vo.SkillDeploymentVO;
import java.util.List;

public interface SkillDeploymentService {
    SkillDeploymentVO deploy(String scopeType,Long targetId,Long versionId,Long operatorId);
    SkillDeploymentVO remove(Long deploymentId,Long operatorId);
    List<SkillDeploymentVO> list(String keyword,String status,String scopeType,Long operatorId);
}
