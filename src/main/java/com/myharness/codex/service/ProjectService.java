package com.myharness.codex.service;

import com.myharness.codex.entity.dto.CreateProjectDTO;
import com.myharness.codex.entity.vo.ProjectVO;
import java.util.List;

public interface ProjectService {
    ProjectVO createProject(CreateProjectDTO dto,Long operatorId);
    ProjectVO getProject(Long projectId,Long operatorId);
    List<ProjectVO> getProjects(Long operatorId);
    ProjectVO retryPreparation(Long projectId,Long operatorId);
}
