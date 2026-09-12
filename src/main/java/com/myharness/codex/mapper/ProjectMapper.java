package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.ProjectPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface ProjectMapper {
    String PROJECT_FROM = "FROM codex_project p JOIN agent_device d ON d.id=p.device_id JOIN agent_workspace w ON w.id=p.workspace_id ";
    // Compare ASCII device codes with Unicode keywords while preserving case-sensitive matching.
    String PROJECT_KEYWORD_MATCH = "LOCATE(#{keyword},p.project_name)>0 OR LOCATE(#{keyword},d.device_name)>0 " +
            "OR LOCATE(#{keyword},CONVERT(d.device_code USING utf8mb4) COLLATE utf8mb4_bin)>0 " +
            "OR LOCATE(#{keyword},w.workspace_name)>0 OR LOCATE(#{keyword},w.root_path)>0";
    String OWNED_PROJECT_FILTER = "WHERE p.user_id=#{userId} AND EXISTS(SELECT 1 FROM user_device_assignment a " +
            "WHERE a.user_id=p.user_id AND a.device_id=p.device_id AND a.status='ENABLED') " +
            "AND (#{keyword}='' OR " + PROJECT_KEYWORD_MATCH + " OR EXISTS(SELECT 1 FROM conversation search_conversation " +
            "WHERE search_conversation.project_id=p.id AND search_conversation.user_id=#{userId} AND LOCATE(#{keyword},search_conversation.title)>0)) ";
    @Select(PROJECT_SELECT + "WHERE EXISTS (SELECT 1 FROM conversation c JOIN conversation_turn t ON t.conversation_id=c.id WHERE c.project_id=p.id AND t.status='CREATED' AND t.created_at<#{deadline} AND (t.preparation_phase IS NULL OR t.created_at<DATE_SUB(#{now}, INTERVAL 180 SECOND)))")
    List<ProjectPO> timedOutTurnProjects(@Param("deadline") java.time.LocalDateTime deadline,@Param("now") java.time.LocalDateTime now);
    @Select(PROJECT_SELECT + "WHERE p.device_id=#{deviceId} AND p.status='ACTIVE'")
    List<ProjectPO> selectForDevice(Long deviceId);
    String PROJECT_SELECT = "SELECT p.id,p.user_id,p.device_id,p.workspace_id,p.project_name,p.status,p.isolation_mode,p.created_at,p.request_key,w.failure_code,w.failure_message," +
            "d.device_code,d.device_name,d.status device_status,w.workspace_name,w.root_path,w.status workspace_status," +
            "COALESCE((SELECT MAX(activity.last_activity_at) FROM conversation activity " +
            "WHERE activity.project_id=p.id AND activity.user_id=p.user_id),p.created_at) last_activity_at," +
            "(SELECT COUNT(*) FROM conversation c WHERE c.project_id=p.id) conversation_count " +
            PROJECT_FROM;

    @Insert("INSERT INTO codex_project(user_id,device_id,workspace_id,project_name,status,isolation_mode,request_key) " +
            "VALUES(#{userId},#{deviceId},#{workspaceId},#{projectName},'ACTIVE',#{isolationMode},#{requestKey})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(ProjectPO project);

    @Select(PROJECT_SELECT + "WHERE p.id=#{id} AND p.user_id=#{userId}")
    ProjectPO selectOwned(@Param("id") Long id,@Param("userId") Long userId);

    @Select(PROJECT_SELECT + OWNED_PROJECT_FILTER + "ORDER BY last_activity_at DESC,p.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ProjectPO> selectOwnedProjects(@Param("userId") Long userId,@Param("keyword") String keyword,
            @Param("limit") int limit,@Param("offset") long offset);

    @Select("SELECT COUNT(*) " + PROJECT_FROM + OWNED_PROJECT_FILTER)
    long countOwnedProjects(@Param("userId") Long userId,@Param("keyword") String keyword);

    @Select(PROJECT_SELECT+"WHERE p.user_id=#{userId} AND p.request_key=#{requestKey}")
    ProjectPO selectRequest(@Param("userId") Long userId,@Param("requestKey") String requestKey);
}
