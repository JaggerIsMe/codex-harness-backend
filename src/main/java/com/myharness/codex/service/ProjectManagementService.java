package com.myharness.codex.service;

import com.myharness.codex.entity.dto.UpdateConversationDTO;
import com.myharness.codex.entity.dto.UpdateProjectDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.entity.vo.ConversationVO;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.ProjectManagementMapper;
import com.myharness.codex.mapper.ProjectMapper;
import com.myharness.codex.security.AuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns display names and deletion of private Project/Conversation records, without deleting Workspace files. */
@Service
public class ProjectManagementService {
    private final ProjectManagementMapper mutations;
    private final ProjectMapper projects;
    private final ConversationMapper conversations;
    private final AuthorizationService access;
    private final TransactionTemplate transactions;

    public ProjectManagementService(ProjectManagementMapper mutations,ProjectMapper projects,ConversationMapper conversations,
            AuthorizationService access,TransactionTemplate transactions) {
        this.mutations=mutations;this.projects=projects;this.conversations=conversations;
        this.access=access;this.transactions=transactions;
    }

    public ProjectVO updateProject(Long id,UpdateProjectDTO input,Long userId) {
        access.requirePermission(userId,"project:update");
        String name=name(input.projectName(),128,"项目名称");
        try {
            return transactions.execute(tx -> {
                lockOwnedProject(id,userId);
                mutations.renameProject(id,userId,name);
                return new ProjectVO(projects.selectOwned(id,userId));
            });
        } catch(DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT,"已有同名项目，请更换名称");
        }
    }

    public ConversationVO updateConversation(Long projectId,Long id,UpdateConversationDTO input,Long userId) {
        access.requirePermission(userId,"conversation:update");
        String title=name(input.title(),255,"会话名称");
        return transactions.execute(tx -> {
            lockOwnedProject(projectId,userId);
            lockOwnedConversation(projectId,id,userId);
            mutations.renameConversation(projectId,id,userId,title);
            return new ConversationVO(conversations.selectOwnedConversation(projectId,id,userId));
        });
    }

    public void deleteConversation(Long projectId,Long id,Long userId) {
        access.requirePermission(userId,"conversation:delete");
        transactions.executeWithoutResult(tx -> {
            lockOwnedProject(projectId,userId);
            lockOwnedConversation(projectId,id,userId);
            requireIdle(id);
            deleteConversationRecords(id);
        });
    }

    public void deleteProject(Long id,Long userId) {
        access.requirePermission(userId,"project:delete");
        transactions.executeWithoutResult(tx -> {
            ProjectPO project=lockOwnedProject(id,userId);
            var ids=mutations.lockProjectConversations(id);
            for(Long conversationId:ids) requireIdle(conversationId);
            for(Long conversationId:ids) deleteConversationRecords(conversationId);
            mutations.deleteExpertBindings(id);
            mutations.cancelProjectTransfers(id);
            mutations.disableWorkspace(project.getWorkspaceId(),project.getDeviceId());
            mutations.deleteProject(id,userId);
        });
    }

    private ProjectPO lockOwnedProject(Long id,Long userId) {
        ProjectPO value=mutations.lockProject(id,userId);
        if(value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"项目不存在");
        access.requireDevice(userId,value.getDeviceId());
        return value;
    }

    private ConversationPO lockOwnedConversation(Long projectId,Long id,Long userId) {
        ConversationPO value=mutations.lockConversation(projectId,id,userId);
        if(value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"会话不存在");
        return value;
    }

    private void requireIdle(Long id) {
        if(mutations.lockActiveTurn(id)!=null)
            throw new BusinessException(ErrorCode.CONFLICT,"会话中有正在执行或等待审批的任务，请先停止任务后再删除");
    }

    private void deleteConversationRecords(Long id) {
        mutations.detachConversationTransfers(id);
        mutations.deleteMessageAttachments(id);
        mutations.deleteAttachments(id);
        mutations.deleteConversation(id);
    }

    private String name(String value,int limit,String label) {
        if(value==null || value.isBlank() || value.length()>limit)
            throw new BusinessException(ErrorCode.INVALID_REQUEST,label+"不能为空且不能超过 "+limit+" 个字符");
        return value.trim();
    }
}
