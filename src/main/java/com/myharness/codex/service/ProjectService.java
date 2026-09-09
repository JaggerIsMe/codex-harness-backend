package com.myharness.codex.service;

import com.myharness.codex.entity.dto.CreateProjectDTO;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.entity.vo.PageVO;

public interface ProjectService {
    ProjectVO createProject(CreateProjectDTO dto,Long operatorId);
    ProjectVO getProject(Long projectId,Long operatorId);
    PageVO<ProjectVO> getProjects(Long operatorId,int page,int size,String keyword);
    ProjectVO retryPreparation(Long projectId,Long operatorId);
}
