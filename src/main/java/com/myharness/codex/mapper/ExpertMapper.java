package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.TurnExpertVO;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ExpertMapper {
    @Select("SELECT project_id FROM project_expert_binding WHERE expert_id=#{id} ORDER BY project_id") List<Long> boundProjects(Long id);
    @Select("SELECT DISTINCT sv.id,sv.skill_id,sv.version,sv.status FROM project_expert_binding b JOIN expert_version v ON v.id=b.expert_version_id JOIN expert e ON e.id=b.expert_id JOIN skill_version sv ON JSON_CONTAINS(v.skill_version_ids,CAST(sv.id AS JSON),'$') WHERE b.project_id=#{id} AND e.status='PUBLISHED'") List<SkillVersionPO> requiredSkills(Long id);
    @Select("SELECT * FROM expert WHERE (#{keyword}='' OR name LIKE CONCAT('%',#{keyword},'%') OR description LIKE CONCAT('%',#{keyword},'%')) ORDER BY id DESC LIMIT 200")
    List<ExpertPO> list(@Param("keyword") String keyword);
    @Select("SELECT e.id,v.name,v.description,e.status,e.published_version_id,e.revision FROM expert e JOIN expert_version v ON v.id=e.published_version_id JOIN user_expert_assignment a ON a.expert_id=e.id AND a.user_id=#{userId} AND a.status='ENABLED' WHERE e.status='PUBLISHED' AND (#{keyword}='' OR v.name LIKE CONCAT('%',#{keyword},'%') OR v.description LIKE CONCAT('%',#{keyword},'%')) ORDER BY e.id DESC LIMIT 200")
    List<ExpertPO> market(@Param("keyword") String keyword,@Param("userId") Long userId);
    @Select("SELECT * FROM expert WHERE id=#{id}") ExpertPO get(Long id);
    @Select("SELECT * FROM expert WHERE id=#{id} FOR UPDATE") ExpertPO lock(Long id);
    @Insert("INSERT INTO expert(name,description,system_prompt,skill_version_ids,mcp_version_ids,created_by) VALUES(#{name},#{description},#{systemPrompt},#{skillVersionIds},#{mcpVersionIds},#{createdBy})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(ExpertPO value);
    @Update("UPDATE expert SET name=#{name},description=#{description},system_prompt=#{systemPrompt},skill_version_ids=#{skillVersionIds},mcp_version_ids=#{mcpVersionIds},draft_changed=1,revision=revision+1 WHERE id=#{id}")
    int draft(ExpertPO value);
    @Select("SELECT COALESCE(MAX(version_no),0)+1 FROM expert_version WHERE expert_id=#{id}") long nextVersion(Long id);
    @Insert("INSERT INTO expert_version(expert_id,version_no,name,description,system_prompt,skill_version_ids,mcp_version_ids,compatible_upgrade) VALUES(#{expertId},#{versionNo},#{name},#{description},#{systemPrompt},#{skillVersionIds},#{mcpVersionIds},#{compatibleUpgrade})")
    @Options(useGeneratedKeys=true,keyProperty="id") int publish(ExpertVersionPO value);
    @Update("UPDATE expert SET published_version_id=#{versionId},status='PUBLISHED',draft_changed=0,revision=revision+1 WHERE id=#{id}")
    int published(@Param("id") Long id,@Param("versionId") Long versionId);
    @Update("UPDATE expert SET status=#{status},revision=revision+1 WHERE id=#{id}")
    int status(@Param("id") Long id,@Param("status") String status);
    @Select("SELECT v.*,e.status FROM expert_version v JOIN expert e ON e.id=v.expert_id WHERE v.id=#{id}") ExpertVersionPO version(Long id);
    @Select("SELECT v.*,e.status FROM expert_version v JOIN expert e ON e.id=v.expert_id WHERE v.expert_id=#{id} ORDER BY version_no DESC") List<ExpertVersionPO> versions(Long id);
    @Select("SELECT expert_revision FROM codex_project WHERE id=#{id} FOR UPDATE") Long lockProject(Long id);
    @Select("SELECT expert_revision FROM codex_project WHERE id=#{id}") Long projectRevision(Long id);
    @Update("UPDATE codex_project SET expert_revision=expert_revision+1 WHERE id=#{id}") int bumpProject(Long id);
    @Select("SELECT b.*,v.name,v.description,v.version_no,e.status,latest.id latest_version_id,latest.version_no latest_version_no " +
            "FROM project_expert_binding b JOIN expert_version v ON v.id=b.expert_version_id JOIN expert e ON e.id=b.expert_id " +
            "LEFT JOIN expert_version latest ON latest.id=e.published_version_id WHERE b.project_id=#{id} ORDER BY b.expert_id")
    List<ProjectExpertPO> bindings(Long id);
    @Insert("INSERT INTO project_expert_binding(project_id,expert_id,expert_version_id) VALUES(#{projectId},#{expertId},#{expertVersionId}) ON DUPLICATE KEY UPDATE expert_version_id=VALUES(expert_version_id)") int bind(ProjectExpertPO value);
    @Update("UPDATE conversation c JOIN expert_version current_version ON current_version.id=c.selected_expert_version_id " +
            "JOIN expert_version target_version ON target_version.id=#{targetVersionId} AND target_version.expert_id=#{expertId} " +
            "SET c.selected_expert_version_id=target_version.id,c.expert_selection_revision=c.expert_selection_revision+1 " +
            "WHERE c.project_id=#{projectId} AND c.selected_expert_id=#{expertId} " +
            "AND current_version.expert_id=#{expertId} AND current_version.version_no<target_version.version_no " +
            "AND NOT EXISTS(SELECT 1 FROM expert_version candidate_version WHERE candidate_version.expert_id=#{expertId} " +
            "AND candidate_version.version_no>current_version.version_no AND candidate_version.version_no<=target_version.version_no AND candidate_version.compatible_upgrade=0)")
    int upgradeCompatibleConversations(@Param("projectId") Long projectId,@Param("expertId") Long expertId,
                                       @Param("targetVersionId") Long targetVersionId);
    @Delete("DELETE FROM project_expert_binding WHERE project_id=#{projectId} AND expert_id=#{expertId}") int unbind(@Param("projectId") Long projectId,@Param("expertId") Long expertId);
    @Select("SELECT COUNT(*) FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id WHERE c.project_id=#{id} AND t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')") int activeTurns(Long id);
    @Select("SELECT t.id turn_id,t.expert_version_id,t.expert_name FROM conversation_turn t WHERE t.conversation_id=#{id} ORDER BY t.id") List<TurnExpertVO> turnExperts(Long id);
}
