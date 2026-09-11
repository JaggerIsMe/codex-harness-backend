package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpertServiceTest {
    ExpertMapper mapper; ProjectMapper projects; ConversationMapper conversations; SkillMapper skills;
    AgentDeviceMapper devices; RbacMapper rbac; AuthorizationService access; ExpertService service; ConversationPO c;
    McpConfigurationService mcp;
    List<ProjectExpertPO> bindings; Map<Long,ExpertVersionPO> versions;

    @BeforeEach void setup() {
        mapper=mock(ExpertMapper.class);projects=mock(ProjectMapper.class);conversations=mock(ConversationMapper.class);
        skills=mock(SkillMapper.class);devices=mock(AgentDeviceMapper.class);rbac=mock(RbacMapper.class);access=mock(AuthorizationService.class);mcp=mock(McpConfigurationService.class);
        when(mcp.runtimes(anyList())).thenReturn(List.of());
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        doAnswer(i -> {((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)i.getArgument(0)).accept(mock(org.springframework.transaction.TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
        var properties=new AgentProperties();properties.setPublicBaseUrl("http://localhost:9010");
        service=new ExpertService(mapper,projects,conversations,skills,devices,rbac,access,tx,new ObjectMapper(),properties,mcp);
        var p=new ProjectPO();p.setId(1L);p.setDeviceId(8L);p.setUserId(2L);p.setStatus("ACTIVE");
        when(projects.selectOwned(1L,2L)).thenReturn(p);when(mapper.lockProject(1L)).thenReturn(4L);
        var device=new AgentDevicePO();device.setProjectExperts(true);when(devices.selectById(8L)).thenReturn(device);
        when(rbac.expertAssigned(eq(2L),anyLong())).thenReturn(1);
        c=new ConversationPO();c.setId(3L);c.setProjectId(1L);c.setUserId(2L);c.setDeviceId(8L);c.setExpertSelectionRevision(0L);
        when(conversations.selectOwnedConversation(1L,3L,2L)).thenReturn(c);when(conversations.lockConversation(3L)).thenReturn(c);
        bindings=new ArrayList<>();versions=new HashMap<>();when(mapper.bindings(1L)).thenAnswer(i -> List.copyOf(bindings));
        when(mapper.version(anyLong())).thenAnswer(i -> versions.get(i.getArgument(0)));
        addExpert(10L,100L,"Java 专家","Java instructions","[]");addExpert(20L,200L,"数据库专家","SQL instructions","[]");
    }

    void addExpert(Long id,Long versionId,String name,String prompt,String skillIds) {
        var v=new ExpertVersionPO();v.setId(versionId);v.setExpertId(id);v.setName(name);v.setSystemPrompt(prompt);v.setStatus("PUBLISHED");v.setSkillVersionIds(skillIds);v.setVersionNo(1L);versions.put(versionId,v);
        var b=new ProjectExpertPO();b.setProjectId(1L);b.setExpertId(id);b.setExpertVersionId(versionId);b.setName(name);b.setStatus("PUBLISHED");b.setVersionNo(1L);b.setLatestVersionId(versionId);b.setLatestVersionNo(1L);bindings.add(b);
    }

    @Test void conversationUsesItsStoredExpertVersionUntilACompatibleUpgradeChangesIt() {
        service.bindAtCreation(c,10L);
        assertEquals(10L,c.getSelectedExpertId());assertEquals(100L,c.getSelectedExpertVersionId());
        var first=service.freeze(c,4L);bindings.getFirst().setExpertVersionId(200L);var later=service.freeze(c,5L);
        assertEquals(100L,later.getExpertVersionId());assertEquals(first.getRuntimeKey(),later.getRuntimeKey());assertEquals("Java instructions",later.getSystemPrompt());
    }

    @Test void projectUpgradeRequestsCompatibleConversationUpgrade() {
        var next=new ExpertVersionPO();next.setId(101L);next.setExpertId(10L);next.setVersionNo(2L);
        next.setName("Java 专家");next.setSystemPrompt("Java v2");next.setSkillVersionIds("[]");next.setStatus("PUBLISHED");next.setCompatibleUpgrade(true);
        versions.put(101L,next);
        var input=new ExpertBindingDTO();input.setExpertVersionId(101L);input.setProjectRevision(4L);
        service.bind(1L,input,2L);
        verify(mapper).upgradeCompatibleConversations(1L,10L,101L);
    }

    @Test void projectExpertListAdvertisesTheLatestPublishedVersion() {
        bindings.getFirst().setLatestVersionId(101L);bindings.getFirst().setLatestVersionNo(2L);

        var result=service.projectExperts(1L,2L).experts().getFirst();

        assertTrue(result.upgradeAvailable());assertEquals(101L,result.latestVersionId());assertEquals(2L,result.latestVersionNo());
    }

    @Test void adminListReportsSkillUpdateWhenPublishedExpertPinsDisabledPreviousVersion() {
        skill(50L,7L);skill(51L,7L);
        skills.selectVersion(50L).setStatus("DISABLED");
        var skillVersions=List.of(skills.selectVersion(51L),skills.selectVersion(50L));
        when(skills.selectVersions(7L)).thenReturn(skillVersions);
        versions.get(100L).setSkillVersionIds("[50]");
        var expert=new ExpertPO();expert.setId(10L);expert.setName("Java 专家");expert.setStatus("PUBLISHED");
        expert.setPublishedVersionId(100L);expert.setSkillVersionIds("[50]");expert.setRevision(1L);
        when(mapper.list("")).thenReturn(List.of(expert));

        var result=service.list("",true,2L).getFirst();

        assertEquals(1,result.skillUpdates().size(),"专家列表应提示已发布版本的 Skill 依赖需要更新并重新发布");
        assertEquals(50L,result.skillUpdates().getFirst().currentVersionId());
        assertEquals(51L,result.skillUpdates().getFirst().availableVersionId());
        assertEquals("PUBLISHED",result.status());
        assertEquals(List.of(50L),result.skillVersionIds());

        when(mapper.lock(10L)).thenReturn(expert);when(mapper.get(10L)).thenReturn(expert);
        var draft=new ExpertDraftDTO();draft.setName("Java 专家");draft.setSystemPrompt("Review Java");
        draft.setSkillVersionIds(List.of(51L));draft.setRevision(1L);
        assertEquals(1,service.save(10L,draft,2L).skillUpdates().size());
        assertEquals(1,service.list("",true,2L).getFirst().skillUpdates().size());

        when(mapper.nextVersion(10L)).thenReturn(2L);
        doAnswer(i -> {var version=(ExpertVersionPO)i.getArgument(0);version.setId(101L);versions.put(101L,version);return 1;}).when(mapper).publish(any());
        doAnswer(i -> {expert.setPublishedVersionId(101L);expert.setStatus("PUBLISHED");return 1;}).when(mapper).published(10L,101L);
        assertTrue(service.publish(10L,1L,true,2L).skillUpdates().isEmpty());
        assertTrue(service.list("",true,2L).getFirst().skillUpdates().isEmpty());
        assertEquals("[50]",versions.get(100L).getSkillVersionIds());
        assertEquals("[51]",versions.get(101L).getSkillVersionIds());
        verify(mapper,never()).bind(any());
    }

    @Test void adminListDoesNotAdvertiseUnavailableSkillVersionsOrUnpublishedExperts() {
        skill(50L,7L);skill(51L,7L);
        var current=skills.selectVersion(50L);var replacement=skills.selectVersion(51L);
        replacement.setStatus("DISABLED");
        when(skills.selectVersions(7L)).thenReturn(List.of(replacement,current));
        versions.get(100L).setSkillVersionIds("[50]");
        var expert=new ExpertPO();expert.setId(10L);expert.setPublishedVersionId(100L);
        when(mapper.list("")).thenReturn(List.of(expert));
        assertTrue(service.list("",true,2L).getFirst().skillUpdates().isEmpty());

        current.setStatus("DISABLED");
        assertTrue(service.list("",true,2L).getFirst().skillUpdates().isEmpty());
        replacement.setStatus("ACTIVE");skills.selectSkill(7L).setStatus("DISABLED");
        assertTrue(service.list("",true,2L).getFirst().skillUpdates().isEmpty());
        skills.selectSkill(7L).setStatus("ENABLED");expert.setPublishedVersionId(null);
        assertTrue(service.list("",true,2L).getFirst().skillUpdates().isEmpty());
    }

    @Test void marketDoesNotExposeAdministratorSkillUpdateDetails() {
        var expert=new ExpertPO();expert.setId(10L);expert.setPublishedVersionId(100L);
        when(mapper.market("",2L)).thenReturn(List.of(expert));
        assertTrue(service.list("",false,2L).getFirst().skillUpdates().isEmpty());
        assertTrue(service.list("",false,2L).getFirst().mcpUpdates().isEmpty());
        verify(skills,never()).selectVersions(anyLong());
        verify(mcp,never()).updates(anyList());
    }

    @Test void unavailableOrUnassignedExpertBlocksNewAndExistingConversations() {
        service.bindAtCreation(c,10L);bindings.getFirst().setStatus("UNPUBLISHED");
        assertThrows(BusinessException.class,()->service.freeze(c,5L));assertFalse(service.selection(1L,3L,2L).available());
        bindings.getFirst().setStatus("PUBLISHED");when(rbac.expertAssigned(2L,10L)).thenReturn(0);
        assertThrows(BusinessException.class,()->service.freeze(c,5L));
        var fresh=new ConversationPO();fresh.setProjectId(1L);fresh.setUserId(2L);fresh.setDeviceId(8L);
        assertThrows(BusinessException.class,()->service.bindAtCreation(fresh,10L));
    }

    @Test void projectBindingRequiresUserAssignmentAndPublishedStatus() {
        when(rbac.expertAssigned(2L,10L)).thenReturn(0);
        var input=new ExpertBindingDTO();input.setExpertVersionId(100L);input.setProjectRevision(4L);
        assertThrows(BusinessException.class,()->service.bind(1L,input,2L));verify(mapper,never()).bind(any());
        when(rbac.expertAssigned(2L,10L)).thenReturn(1);versions.get(100L).setStatus("UNPUBLISHED");
        assertThrows(BusinessException.class,()->service.bind(1L,input,2L));
    }

    @Test void unpublishInvalidatesEveryBoundProject() {
        var e=new ExpertPO();e.setId(10L);e.setRevision(1L);e.setStatus("PUBLISHED");e.setSkillVersionIds("[]");when(mapper.lock(10L)).thenReturn(e);when(mapper.get(10L)).thenReturn(e);
        when(mapper.boundProjects(10L)).thenReturn(List.of(1L,2L));when(mapper.lockProject(2L)).thenReturn(3L);
        service.status(10L,"UNPUBLISHED",1L,2L);
        verify(mapper).bumpProject(1L);verify(mapper).bumpProject(2L);verify(mapper).status(10L,"UNPUBLISHED");
    }

    @Test void eachConversationLoadsOnlyItsPinnedExpertSkills() {
        skill(50L,7L);skill(51L,8L);versions.get(100L).setSkillVersionIds("[50]");versions.get(200L).setSkillVersionIds("[51]");
        service.bindAtCreation(c,10L);assertEquals(List.of(50L),service.freeze(c,4L).getSkills().stream().map(ExpertRuntimeSkillDTO::getVersionId).toList());
        var other=new ConversationPO();other.setId(4L);other.setProjectId(1L);other.setUserId(2L);other.setDeviceId(8L);service.bindAtCreation(other,20L);
        assertEquals(List.of(51L),service.freeze(other,4L).getSkills().stream().map(ExpertRuntimeSkillDTO::getVersionId).toList());
    }

    @Test void projectInstalledSkillConflictBlocksBinding() {
        skill(50L,7L);skill(51L,7L);versions.get(100L).setSkillVersionIds("[50]");var installed=skills.selectVersion(51L);when(mapper.installedSkills(1L)).thenReturn(List.of(installed));
        var input=new ExpertBindingDTO();input.setExpertVersionId(100L);input.setProjectRevision(4L);
        assertThrows(BusinessException.class,()->service.bind(1L,input,2L));verify(mapper,never()).bind(any());
    }

    @Test void knowledgeBindingsAreReservedAndCannotBeConfigured() {
        var input=new ExpertDraftDTO();input.setName("A");input.setSystemPrompt("B");input.setKnowledgeBindings(List.of("knowledge"));
        assertThrows(BusinessException.class,()->service.save(null,input,2L));verify(mapper,never()).insert(any());
    }

    @Test void frozenRuntimeIncludesPinnedMcpVersionAndRequiresV4DeviceCapability() {
        var runtime=new McpRuntimeDTO();runtime.setConfigurationId(7L);runtime.setConfigurationVersionId(70L);
        runtime.setServerCode("github");runtime.setConfigDigest("b".repeat(64));runtime.setTransportType("STDIO");runtime.setCommand("npx");
        when(mcp.runtimes(List.of(70L))).thenReturn(List.of(runtime));versions.get(100L).setMcpVersionIds("[70]");
        assertThrows(BusinessException.class,()->service.bindAtCreation(c,10L));
        devices.selectById(8L).setExpertMcp(true);
        service.bindAtCreation(c,10L);

        var frozen=service.freeze(c,4L);

        assertEquals(4,frozen.getSchemaVersion());assertEquals(70L,frozen.getMcpServers().getFirst().getConfigurationVersionId());
        assertEquals(64,frozen.getRuntimeKey().length());
    }

    @Test void editingPublishedExpertReturnsItToDraft() {
        var expert=new ExpertPO();expert.setId(10L);expert.setName("Java");expert.setSystemPrompt("old");expert.setSkillVersionIds("[]");expert.setRevision(3L);expert.setStatus("PUBLISHED");
        when(mapper.lock(10L)).thenReturn(expert);when(mapper.get(10L)).thenReturn(expert);when(mapper.boundProjects(10L)).thenReturn(List.of(1L));
        var input=new ExpertDraftDTO();input.setName("Java v2 draft");input.setSystemPrompt("new");input.setRevision(3L);

        var saved=service.save(10L,input,2L);

        assertEquals("DRAFT",saved.status());
        verify(mapper).draft(expert);verify(mapper).lockProject(1L);verify(mapper).bumpProject(1L);
    }

    @Test void expertCannotSelectTwoVersionsOfTheSameSkill() {
        skill(50L,7L);skill(51L,7L);
        var input=new ExpertDraftDTO();input.setName("Java");input.setSystemPrompt("prompt");input.setSkillVersionIds(List.of(50L,51L));

        assertThrows(BusinessException.class,()->service.save(null,input,2L));

        verify(mapper,never()).insert(any());
    }

    @Test void publishFreezesDraftAndRecordsCompatibilityWithoutUpgradingProjects() {
        var e=new ExpertPO();e.setId(10L);e.setName("Java");e.setDescription("desc");e.setSystemPrompt("version one");e.setSkillVersionIds("[]");e.setRevision(1L);e.setStatus("PUBLISHED");
        when(mapper.lock(10L)).thenReturn(e);when(mapper.get(10L)).thenReturn(e);when(mapper.nextVersion(10L)).thenReturn(2L);
        doAnswer(i -> {((ExpertVersionPO)i.getArgument(0)).setId(101L);return 1;}).when(mapper).publish(any());
        service.publish(10L,1L,true,2L);e.setSystemPrompt("new draft");
        var capture=org.mockito.ArgumentCaptor.forClass(ExpertVersionPO.class);verify(mapper).publish(capture.capture());
        assertEquals("version one",capture.getValue().getSystemPrompt());assertTrue(capture.getValue().getCompatibleUpgrade());verify(mapper,never()).bind(any());
    }

    @Test void firstPublishedVersionCannotBeMarkedAsAnUpgrade() {
        var e=new ExpertPO();e.setId(10L);e.setName("Java");e.setSystemPrompt("first");e.setSkillVersionIds("[]");e.setRevision(0L);e.setStatus("DRAFT");
        when(mapper.lock(10L)).thenReturn(e);when(mapper.get(10L)).thenReturn(e);when(mapper.nextVersion(10L)).thenReturn(1L);
        doAnswer(i -> {((ExpertVersionPO)i.getArgument(0)).setId(100L);return 1;}).when(mapper).publish(any());
        service.publish(10L,0L,true,2L);
        var capture=org.mockito.ArgumentCaptor.forClass(ExpertVersionPO.class);verify(mapper).publish(capture.capture());
        assertFalse(capture.getValue().getCompatibleUpgrade());
    }

    void skill(Long id,Long skillId) {
        var v=new SkillVersionPO();v.setId(id);v.setSkillId(skillId);v.setVersion("v"+id);v.setStatus("ACTIVE");v.setSha256("a".repeat(64));when(skills.selectVersion(id)).thenReturn(v);
        var s=new SkillPO();s.setId(skillId);s.setSkillName("skill-"+skillId);s.setStatus("ENABLED");when(skills.selectSkill(skillId)).thenReturn(s);
    }
}
