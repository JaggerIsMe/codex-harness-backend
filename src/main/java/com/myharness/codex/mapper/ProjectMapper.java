package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.ProjectPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface ProjectMapper {
    String PROJECT_SELECT = "SELECT p.id,p.user_id,p.device_id,p.workspace_id,p.project_name,p.status,p.isolation_mode,p.created_at,p.request_key,w.failure_code,w.failure_message," +
            "d.device_code,d.device_name,d.status device_status,w.workspace_name,w.root_path,w.status workspace_status," +
            "(SELECT COUNT(*) FROM conversation c WHERE c.project_id=p.id) conversation_count " +
            "FROM codex_project p JOIN agent_device d ON d.id=p.device_id JOIN agent_workspace w ON w.id=p.workspace_id ";

    @Insert("INSERT INTO codex_project(user_id,device_id,workspace_id,project_name,status,isolation_mode,request_key) " +
            "VALUES(#{userId},#{deviceId},#{workspaceId},#{projectName},'ACTIVE','WINDOWS_PROJECT_PROFILE',#{requestKey})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(ProjectPO project);

    @Select(PROJECT_SELECT + "WHERE p.id=#{id} AND p.user_id=#{userId}")
    ProjectPO selectOwned(@Param("id") Long id,@Param("userId") Long userId);

    @Select(PROJECT_SELECT + "WHERE p.user_id=#{userId} AND EXISTS(SELECT 1 FROM user_device_assignment a WHERE a.user_id=p.user_id AND a.device_id=p.device_id AND a.status='ENABLED') ORDER BY p.updated_at DESC,p.id DESC")
    List<ProjectPO> selectOwnedProjects(@Param("userId") Long userId);

    @Select(PROJECT_SELECT+"WHERE p.user_id=#{userId} AND p.request_key=#{requestKey}")
    ProjectPO selectRequest(@Param("userId") Long userId,@Param("requestKey") String requestKey);
}
