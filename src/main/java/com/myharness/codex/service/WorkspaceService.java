package com.myharness.codex.service;

import com.myharness.codex.entity.dto.CreateWorkspaceDTO;
import com.myharness.codex.entity.vo.AgentWorkspaceRootVO;
import com.myharness.codex.entity.vo.AgentWorkspaceVO;

import java.util.List;

public interface WorkspaceService {
    List<AgentWorkspaceVO> list(Long deviceId);
    List<AgentWorkspaceRootVO> listRoots(Long deviceId);
    AgentWorkspaceVO create(Long deviceId, CreateWorkspaceDTO dto, Long operatorId);
}
