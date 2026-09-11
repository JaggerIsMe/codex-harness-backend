package com.myharness.codex.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ExpertService {
    private final ExpertMapper mapper;
    private final ProjectMapper projects;
    private final ConversationMapper conversations;
    private final SkillMapper skills;
    private final AgentDeviceMapper devices;
    private final RbacMapper rbac;
    private final AuthorizationService access;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AgentProperties properties;
    private final McpConfigurationService mcp;

    public ExpertService(ExpertMapper mapper, ProjectMapper projects, ConversationMapper conversations,
                         SkillMapper skills, AgentDeviceMapper devices, RbacMapper rbac, AuthorizationService access,
                         TransactionTemplate tx, ObjectMapper json, AgentProperties properties,McpConfigurationService mcp) {
        this.mapper=mapper; this.projects=projects; this.conversations=conversations; this.skills=skills;
        this.devices=devices; this.rbac=rbac; this.access=access; this.tx=tx; this.json=json; this.properties=properties;this.mcp=mcp;
    }

    public List<ExpertVO> list(String keyword, boolean admin, Long user) {
        access.requirePermission(user,admin ? "expert:manage" : "expert:read");
        return (admin ? mapper.list(trim(keyword)) : mapper.market(trim(keyword),user)).stream().map(e -> view(e,admin)).toList();
    }
    public ExpertVO save(Long id, ExpertDraftDTO input, Long user) {
        access.requirePermission(user,"expert:manage");
        if(input.getMcpBindings()==null || input.getKnowledgeBindings()==null || !input.getKnowledgeBindings().isEmpty())
            throw conflict("知识库暂未接入");
        if(input.getName()==null || input.getName().isBlank() || input.getSystemPrompt()==null || input.getSystemPrompt().isBlank())
            throw conflict("专家名称和系统提示词不能为空");
        mcp.runtimes(input.getMcpBindings());
        return tx.execute(s -> {
            skills.lockCatalog();
            validateSkillIds(input.getSkillVersionIds());
            ExpertPO e=id==null ? new ExpertPO() : required(mapper.lock(id));
            if(id!=null) revision(e.getRevision(),input.getRevision());
            boolean statusChanged=id!=null && !"DRAFT".equals(e.getStatus());
            e.setName(input.getName().trim()); e.setDescription(trim(input.getDescription()));
            e.setSystemPrompt(input.getSystemPrompt().trim()); e.setSkillVersionIds(write(input.getSkillVersionIds()));
            e.setMcpVersionIds(write(input.getMcpBindings()));
            if(id==null) {e.setCreatedBy(user); mapper.insert(e);} else {
                if(statusChanged) for(Long project:mapper.boundProjects(id)) {mapper.lockProject(project); mapper.bumpProject(project);}
                e.setStatus("DRAFT"); mapper.draft(e);
            }
            return view(mapper.get(e.getId()),true);
        });
    }
    public ExpertVO publish(Long id, Long revision, boolean compatibleUpgrade, Long user) {
        access.requirePermission(user,"expert:manage");
        return tx.execute(s -> {
            skills.lockCatalog();
            ExpertPO e=required(mapper.lock(id)); revision(e.getRevision(),revision);
            validateSkillIds(ids(e.getSkillVersionIds()));
            mcp.runtimes(ids(e.getMcpVersionIds()));
            if(!"PUBLISHED".equals(e.getStatus())) {
                for(Long project:mapper.boundProjects(id)) {mapper.lockProject(project); mapper.bumpProject(project);}
            }
            ExpertVersionPO v=new ExpertVersionPO(); v.setExpertId(id); v.setVersionNo(mapper.nextVersion(id));
            v.setName(e.getName()); v.setDescription(e.getDescription()); v.setSystemPrompt(e.getSystemPrompt()); v.setSkillVersionIds(e.getSkillVersionIds());
            v.setMcpVersionIds(e.getMcpVersionIds());
            v.setCompatibleUpgrade(v.getVersionNo()>1 && compatibleUpgrade);
            mapper.publish(v); mapper.published(id,v.getId()); return view(mapper.get(id),true);
        });
    }
    public ExpertVO status(Long id, String status, Long revision, Long user) {
        access.requirePermission(user,"expert:manage");
        if(!Set.of("UNPUBLISHED","DISABLED").contains(status)) throw conflict("专家状态不正确");
        return tx.execute(s -> { skills.lockCatalog(); ExpertPO e=required(mapper.lock(id)); revision(e.getRevision(),revision);
            if("DISABLED".equals(e.getStatus()) && "UNPUBLISHED".equals(status)) throw conflict("禁用专家需重新发布后启用");
            for(Long project:mapper.boundProjects(id)) {mapper.lockProject(project); mapper.bumpProject(project);}
            mapper.status(id,status); return view(mapper.get(id),true); });
    }
    public List<ExpertVersionVO> versions(Long id, Long user) {
        access.requirePermission(user,"expert:read");
        ExpertPO e=required(mapper.get(id));
        if(!access.hasPermission(user,"expert:manage") && (!"PUBLISHED".equals(e.getStatus()) || rbac.expertAssigned(user,id)==0)) throw missing();
        return mapper.versions(id).stream().map(v -> new ExpertVersionVO(v.getId(),v.getExpertId(),v.getVersionNo(),v.getName(),v.getDescription(),ids(v.getSkillVersionIds()),ids(v.getMcpVersionIds()),Boolean.TRUE.equals(v.getCompatibleUpgrade()))).toList();
    }
    public ProjectExpertsVO projectExperts(Long projectId, Long user) {
        ProjectPO p=project(projectId,user);
        return tx.execute(s -> {
            Long revision=mapper.lockProject(projectId);
            return new ProjectExpertsVO(revision,mapper.bindings(projectId).stream().map(b -> {
                String reason=unavailable(b,p,user);
                boolean upgrade="PUBLISHED".equals(b.getStatus()) && b.getLatestVersionNo()!=null && b.getVersionNo()!=null && b.getLatestVersionNo()>b.getVersionNo();
                return new ProjectExpertVO(b.getExpertId(),b.getExpertVersionId(),b.getVersionNo(),
                        b.getLatestVersionId(),b.getLatestVersionNo(),upgrade,b.getName(),b.getDescription(),reason==null,reason);
            }).toList());
        });
    }
    public ProjectExpertsVO bind(Long projectId, ExpertBindingDTO input, Long user) {
        ProjectPO p=project(projectId,user);
        tx.executeWithoutResult(s -> {
            revision(mapper.lockProject(projectId),input.getProjectRevision()); idle(projectId);
            ExpertVersionPO v=mapper.version(input.getExpertVersionId());
            if(v==null || !"PUBLISHED".equals(v.getStatus())) throw conflict("该专家版本未发布或已下架");
            if(rbac.expertAssigned(user,v.getExpertId())==0) throw conflict("管理员未向当前用户分配该专家");
            List<ProjectExpertPO> bindings=new ArrayList<>(mapper.bindings(projectId));
            bindings.removeIf(b -> b.getExpertId().equals(v.getExpertId()));
            ProjectExpertPO b=new ProjectExpertPO(); b.setProjectId(projectId); b.setExpertId(v.getExpertId()); b.setExpertVersionId(v.getId());
            bindings.add(b); skillUnion(p,bindings); mcpUnion(p,List.of(b)); mapper.bind(b);
            mapper.upgradeCompatibleConversations(projectId,v.getExpertId(),v.getId());
            mapper.bumpProject(projectId);
        });
        return projectExperts(projectId,user);
    }
    public ProjectExpertsVO unbind(Long projectId, Long expertId, Long revision, Long user) {
        project(projectId,user);
        tx.executeWithoutResult(s -> { revision(mapper.lockProject(projectId),revision); idle(projectId);
            mapper.unbind(projectId,expertId); mapper.bumpProject(projectId); });
        return projectExperts(projectId,user);
    }
    public ExpertSelectionVO selection(Long projectId, Long conversationId, Long user) {
        ProjectPO p=project(projectId,user); owned(projectId,conversationId,user);
        return tx.execute(s -> {
            Long revision=mapper.lockProject(projectId); ConversationPO c=conversations.lockConversation(conversationId);
            return selectionView(p,c,revision);
        });
    }
    public List<TurnExpertVO> turnExperts(Long projectId, Long conversationId, Long user) {
        project(projectId,user); owned(projectId,conversationId,user); return mapper.turnExperts(conversationId);
    }

    /** Caller holds the project lock before taking the conversation lock. */
    public Long lockProject(Long id) { return mapper.lockProject(id); }
    public void bindAtCreation(ConversationPO c, Long expertId) {
        access.requirePermission(c.getUserId(),"expert:use");
        if(expertId==null) throw conflict("创建会话时必须选择专家");
        ProjectPO p=projects.selectOwned(c.getProjectId(),c.getUserId());
        ProjectExpertPO b=binding(c.getProjectId(),expertId);
        String reason=unavailable(b,p,c.getUserId());
        if(reason!=null) throw conflict(reason);
        c.setSelectedExpertId(expertId);
        c.setSelectedExpertVersionId(b.getExpertVersionId());
        c.setExpertSelectionRevision(1L);
    }
    public ExpertRuntimeDTO freeze(ConversationPO c, Long projectRevision) {
        access.requirePermission(c.getUserId(),"expert:use");
        if(mapper.pendingSkillChanges(c.getProjectId())>0) throw conflict("项目 Skills 正在变更，请完成后再发送");
        ProjectPO p=projects.selectOwned(c.getProjectId(),c.getUserId());
        ExpertRuntimeDTO result=new ExpertRuntimeDTO(); result.setProjectRevision(projectRevision);
        if(c.getSelectedExpertId()==null || c.getSelectedExpertVersionId()==null) throw conflict("该旧会话未绑定专家，请创建新会话");
        ProjectExpertPO currentBinding=binding(c.getProjectId(),c.getSelectedExpertId());
        String reason=accessUnavailable(currentBinding,p,c.getUserId()); if(reason!=null) throw conflict(reason);
        ExpertVersionPO expertVersion=mapper.version(c.getSelectedExpertVersionId());
        if(expertVersion==null || !Objects.equals(expertVersion.getExpertId(),c.getSelectedExpertId()) || !"PUBLISHED".equals(expertVersion.getStatus())) throw conflict("会话绑定的专家版本已不可用");
        ProjectExpertPO pinned=new ProjectExpertPO(); pinned.setProjectId(c.getProjectId()); pinned.setExpertId(c.getSelectedExpertId()); pinned.setExpertVersionId(expertVersion.getId()); pinned.setStatus(currentBinding.getStatus());
        result.setExpertId(c.getSelectedExpertId()); result.setExpertVersionId(expertVersion.getId());
        result.setCompatibleUpgrade(Boolean.TRUE.equals(expertVersion.getCompatibleUpgrade()));
        result.setName(expertVersion.getName()); result.setSystemPrompt(expertVersion.getSystemPrompt());
        List<SkillVersionPO> selectedSkills=skillUnion(p,List.of(pinned));
        List<McpRuntimeDTO> selectedMcp=mcpUnion(p,List.of(pinned));
        AgentDevicePO d=devices.selectById(c.getDeviceId());
        if(d==null || !Boolean.TRUE.equals(d.getProjectExperts())) throw conflict("请升级设备 Agent 以支持项目专家");
        if(!selectedMcp.isEmpty() && !Boolean.TRUE.equals(d.getExpertMcp())) throw conflict("请升级设备 Agent 以支持专家 MCP");
        result.setSkills(selectedSkills.stream().map(v -> {
            ExpertRuntimeSkillDTO skill=new ExpertRuntimeSkillDTO(); skill.setSkillId(v.getSkillId()); skill.setVersionId(v.getId());
            skill.setName(skills.selectSkill(v.getSkillId()).getSkillName()); skill.setVersion(v.getVersion()); skill.setSha256(v.getSha256());
            skill.setDownloadUrl(properties.getPublicBaseUrl().replaceAll("/+$", "")+"/api/v1/agent/skill-versions/"+v.getId()+"/download"); return skill;
        }).toList());
        result.setMcpServers(selectedMcp);
        result.setRuntimeKey(com.myharness.codex.security.SecureDigests.sha256("expert-runtime-v4:"+c.getId()+":"
                +result.getExpertVersionId()+":"
                +selectedSkills.stream().map(v->v.getId()+":"+v.getSha256()).sorted().collect(java.util.stream.Collectors.joining(","))+":"
                +selectedMcp.stream().map(v->v.getConfigurationVersionId()+":"+v.getConfigDigest()).sorted().collect(java.util.stream.Collectors.joining(","))));
        return result;
    }
    public String write(Object value) { try {return json.writeValueAsString(value);} catch(Exception e) {throw new IllegalStateException("Invalid expert configuration",e);} }
    public ExpertRuntimeDTO runtime(String value) {try {return json.readValue(value,ExpertRuntimeDTO.class);} catch(Exception e) {throw new IllegalStateException("Invalid expert snapshot",e);} }
    private ExpertSelectionVO selectionView(ProjectPO p, ConversationPO c, Long revision) {
        if(c.getSelectedExpertId()==null || c.getSelectedExpertVersionId()==null) return new ExpertSelectionVO(c.getSelectedExpertId(),null,null,c.getExpertSelectionRevision(),revision,false,"该旧会话未绑定专家，请创建新会话");
        ProjectExpertPO b=mapper.bindings(p.getId()).stream().filter(v -> v.getExpertId().equals(c.getSelectedExpertId())).findFirst().orElse(null);
        ExpertPO e=mapper.get(c.getSelectedExpertId());
        ExpertVersionPO v=mapper.version(c.getSelectedExpertVersionId());
        String reason=b==null ? "该专家已从项目移除" : accessUnavailable(b,p,c.getUserId());
        if(reason==null && v!=null) {
            ProjectExpertPO pinned=new ProjectExpertPO();pinned.setProjectId(p.getId());pinned.setExpertId(c.getSelectedExpertId());pinned.setExpertVersionId(v.getId());pinned.setStatus(b.getStatus());
            try {skillUnion(p,List.of(pinned));mcpUnion(p,List.of(pinned));} catch(BusinessException exception) {reason=exception.getMessage();}
        }
        if(reason==null && (v==null || !Objects.equals(v.getExpertId(),c.getSelectedExpertId()))) reason="会话绑定的专家版本不存在";
        return new ExpertSelectionVO(c.getSelectedExpertId(),c.getSelectedExpertVersionId(),v==null ? (e==null ? "不可用专家" : e.getName()) : v.getName(),c.getExpertSelectionRevision(),revision,reason==null,reason);
    }
    private String unavailable(ProjectExpertPO b, ProjectPO p, Long userId) {
        String reason=accessUnavailable(b,p,userId);
        if(reason!=null) return reason;
        try { skillUnion(p,List.of(b));mcpUnion(p,List.of(b)); return null; } catch(BusinessException e) {return e.getMessage();}
    }
    private String accessUnavailable(ProjectExpertPO b, ProjectPO p, Long userId) {
        if(!"PUBLISHED".equals(b.getStatus())) return "专家已下架或禁用";
        if(rbac.expertAssigned(userId,b.getExpertId())==0) return "管理员已取消当前用户的专家使用权";
        AgentDevicePO d=devices.selectById(p.getDeviceId());
        if(d==null || !Boolean.TRUE.equals(d.getProjectExperts())) return "请升级设备 Agent 以支持项目专家";
        return null;
    }
    private List<SkillVersionPO> skillUnion(ProjectPO p, List<ProjectExpertPO> bindings) {
        if(p==null) throw missing();
        Map<Long,SkillVersionPO> union=new LinkedHashMap<>();
        for(ProjectExpertPO b:bindings) {
            ExpertVersionPO v=mapper.version(b.getExpertVersionId());
            if(v==null) throw conflict("项目绑定的专家版本不存在");
            if("DISABLED".equals(v.getStatus())) continue;
            for(Long id:ids(v.getSkillVersionIds())) {
                SkillVersionPO skill=validSkill(id); union.putIfAbsent(skill.getId(),skill);
            }
        }
        for(SkillVersionPO installed:mapper.installedSkills(p.getId())) {
            if(union.values().stream().anyMatch(required -> required.getSkillId().equals(installed.getSkillId()) && !required.getId().equals(installed.getId())))
                throw conflict("专家依赖与项目已有 Skill 安装版本冲突");
        }
        return new ArrayList<>(union.values());
    }
    private List<McpRuntimeDTO> mcpUnion(ProjectPO p,List<ProjectExpertPO> bindings) {
        if(p==null) throw missing();List<McpRuntimeDTO> result=new ArrayList<>();Set<Long> versions=new LinkedHashSet<>();
        for(ProjectExpertPO binding:bindings) {
            ExpertVersionPO version=mapper.version(binding.getExpertVersionId());
            if(version==null) throw conflict("项目绑定的专家版本不存在");
            versions.addAll(ids(version.getMcpVersionIds()));
        }
        result.addAll(mcp.runtimes(new ArrayList<>(versions)));
        AgentDevicePO device=devices.selectById(p.getDeviceId());
        if(!result.isEmpty() && (device==null || !Boolean.TRUE.equals(device.getExpertMcp()))) throw conflict("请升级设备 Agent 以支持专家 MCP");
        return result;
    }
    private void validateSkillIds(List<Long> ids) {
        if(ids==null || ids.size()>30 || new HashSet<>(ids).size()!=ids.size()) throw conflict("Skill 版本列表不正确");
        Set<Long> names=new HashSet<>();
        for(Long id:ids) if(!names.add(validSkill(id).getSkillId())) throw conflict("一个专家不能绑定同一 Skill 的多个版本");
    }
    private SkillVersionPO validSkill(Long id) {
        SkillVersionPO v=id==null ? null : skills.selectVersion(id);
        SkillPO s=v==null ? null : skills.selectSkill(v.getSkillId());
        if(v==null || !"ACTIVE".equals(v.getStatus()) || s==null || !"ENABLED".equals(s.getStatus())) throw conflict("依赖的 Skill 版本不存在或已禁用");
        return v;
    }
    private ProjectExpertPO binding(Long projectId, Long expertId) {return mapper.bindings(projectId).stream().filter(b -> b.getExpertId().equals(expertId)).findFirst().orElseThrow(() -> conflict("专家未在当前项目启用"));}
    private ProjectPO project(Long id, Long user) {
        access.requirePermission(user,"expert:use"); ProjectPO p=projects.selectOwned(id,user);
        if(p==null) throw missing(); access.requireDevice(user,p.getDeviceId());
        if(!"ACTIVE".equals(p.getStatus())) throw conflict("项目当前不可用"); return p;
    }
    private void owned(Long projectId, Long id, Long user) {if(conversations.selectOwnedConversation(projectId,id,user)==null) throw missing();}
    private void idle(Long id) {
        if(mapper.activeTurns(id)>0) throw conflict("项目中有运行中或等待审批的任务，请结束后再修改项目专家");
        if(mapper.pendingSkillChanges(id)>0) throw conflict("项目 Skills 正在变更，请完成后再修改项目专家");
    }
    private ExpertVO view(ExpertPO e, boolean admin) {
        ExpertVersionPO published=admin && e.getPublishedVersionId()!=null ? mapper.version(e.getPublishedVersionId()) : null;
        return new ExpertVO(e.getId(),e.getName(),e.getDescription(),e.getStatus(),e.getPublishedVersionId(),e.getRevision(),
                admin?e.getSystemPrompt():null,admin?ids(e.getSkillVersionIds()):List.of(),admin?ids(e.getMcpVersionIds()):List.of(),
                skillUpdates(published),published==null?List.of():mcp.updates(ids(published.getMcpVersionIds())));
    }
    private List<ExpertSkillUpdateVO> skillUpdates(ExpertVersionPO published) {
        if(published==null) return List.of();
        List<ExpertSkillUpdateVO> updates=new ArrayList<>();
        // Compare the immutable release: saving a refreshed draft must not clear the publication notice.
        for(Long id:ids(published.getSkillVersionIds())) {
            SkillVersionPO current=skills.selectVersion(id);
            if(current==null) continue;
            SkillPO skill=skills.selectSkill(current.getSkillId());
            if(skill==null || !"ENABLED".equals(skill.getStatus())) continue;
            skills.selectVersions(skill.getId()).stream().filter(v -> "ACTIVE".equals(v.getStatus())).findFirst()
                    .filter(available -> !Objects.equals(current.getId(),available.getId()))
                    .ifPresent(available -> updates.add(new ExpertSkillUpdateVO(skill.getId(),skill.getSkillName(),
                            current.getId(),current.getVersion(),available.getId(),available.getVersion())));
        }
        return updates;
    }
    private List<Long> ids(String value) {if(value==null || value.isBlank()) return List.of();try {return json.readValue(value,new TypeReference<List<Long>>(){});} catch(Exception e) {throw new IllegalStateException("Invalid expert capability list",e);} }
    private void revision(Long actual, Long expected) {if(!Objects.equals(actual,expected)) throw conflict("配置已变更，请刷新后重试");}
    private ExpertPO required(ExpertPO e) {if(e==null) throw missing(); return e;}
    private BusinessException conflict(String message) {return new BusinessException(ErrorCode.CONFLICT,message);}
    private BusinessException missing() {return new BusinessException(ErrorCode.NOT_FOUND,"专家或项目不存在");}
    private String trim(String value) {return value==null?"":value.trim();}
}
