package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface McpConfigurationMapper {
    @Select("SELECT c.*,v.version_no current_version_no,v.runtime_spec,v.config_digest FROM mcp_configuration c LEFT JOIN mcp_configuration_version v ON v.id=c.current_version_id WHERE (#{keyword}='' OR c.name LIKE CONCAT('%',#{keyword},'%') OR c.server_code LIKE CONCAT('%',#{keyword},'%')) AND (#{status}='' OR c.status=#{status}) ORDER BY c.id DESC LIMIT 200")
    List<McpConfigurationPO> list(@Param("keyword") String keyword,@Param("status") String status);
    @Select("SELECT c.*,v.version_no current_version_no,v.runtime_spec,v.config_digest FROM mcp_configuration c LEFT JOIN mcp_configuration_version v ON v.id=c.current_version_id WHERE c.id=#{id}") McpConfigurationPO get(Long id);
    @Select("SELECT * FROM mcp_configuration WHERE id=#{id} FOR UPDATE") McpConfigurationPO lock(Long id);
    @Select("SELECT COUNT(*) FROM mcp_configuration WHERE server_code=#{serverCode} AND (#{excludeId} IS NULL OR id<>#{excludeId})") int codeExists(@Param("serverCode") String serverCode,@Param("excludeId") Long excludeId);
    @Insert("INSERT INTO mcp_configuration(server_code,name,description,status,created_by) VALUES(#{serverCode},#{name},#{description},'ENABLED',#{createdBy})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(McpConfigurationPO value);
    @Select("SELECT COALESCE(MAX(version_no),0)+1 FROM mcp_configuration_version WHERE mcp_configuration_id=#{id}") long nextVersion(Long id);
    @Insert("INSERT INTO mcp_configuration_version(mcp_configuration_id,version_no,server_code,name,description,runtime_spec,config_digest) VALUES(#{mcpConfigurationId},#{versionNo},#{serverCode},#{name},#{description},#{runtimeSpec},#{configDigest})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertVersion(McpConfigurationVersionPO value);
    @Update("UPDATE mcp_configuration SET server_code=#{serverCode},name=#{name},description=#{description},current_version_id=#{currentVersionId},revision=revision+1 WHERE id=#{id}") int update(McpConfigurationPO value);
    @Update("UPDATE mcp_configuration SET current_version_id=#{versionId} WHERE id=#{id}") int setInitialVersion(@Param("id") Long id,@Param("versionId") Long versionId);
    @Update("UPDATE mcp_configuration SET status=#{status},revision=revision+1 WHERE id=#{id}") int status(@Param("id") Long id,@Param("status") String status);
    @Select("SELECT v.*,v.status version_status,c.status configuration_status FROM mcp_configuration_version v JOIN mcp_configuration c ON c.id=v.mcp_configuration_id WHERE v.id=#{id}") McpConfigurationVersionPO version(Long id);
    @Select("SELECT v.*,v.status version_status,c.status configuration_status FROM mcp_configuration_version v JOIN mcp_configuration c ON c.id=v.mcp_configuration_id WHERE v.mcp_configuration_id=#{id} ORDER BY v.version_no DESC") List<McpConfigurationVersionPO> versions(Long id);
    @Select("SELECT v.*,v.status version_status,c.status configuration_status FROM mcp_configuration_version v JOIN mcp_configuration c ON c.id=v.mcp_configuration_id AND c.current_version_id=v.id WHERE c.status='ENABLED' AND v.status='ACTIVE' ORDER BY c.name,v.version_no DESC") List<McpConfigurationVersionPO> selectableVersions();
    @Select("SELECT id FROM mcp_configuration_version WHERE mcp_configuration_id=#{configurationId} AND version_no<#{versionNo} ORDER BY version_no DESC")
    List<Long> previousVersionIds(@Param("configurationId") Long configurationId,@Param("versionNo") Long versionNo);
    @Update("UPDATE mcp_configuration_version SET status='REVOKED' WHERE id=#{versionId} AND mcp_configuration_id=#{configurationId} AND status='ACTIVE'") int revoke(@Param("configurationId") Long configurationId,@Param("versionId") Long versionId);
    @Update("UPDATE mcp_configuration_version SET status='REVOKED' WHERE mcp_configuration_id=#{configurationId} AND status='ACTIVE'")
    int revokeActiveVersions(Long configurationId);
    @Select("SELECT impacted.project_id FROM (SELECT b.project_id FROM project_expert_binding b JOIN expert_version e ON e.id=b.expert_version_id JOIN mcp_configuration_version v ON JSON_CONTAINS(e.mcp_version_ids,CAST(v.id AS JSON),'$') WHERE v.mcp_configuration_id=#{id} UNION SELECT c.project_id FROM conversation c JOIN expert_version e ON e.id=c.selected_expert_version_id JOIN mcp_configuration_version v ON JSON_CONTAINS(e.mcp_version_ids,CAST(v.id AS JSON),'$') WHERE v.mcp_configuration_id=#{id}) impacted ORDER BY impacted.project_id") List<Long> boundProjectsForConfiguration(Long id);
    @Select("SELECT impacted.project_id FROM (SELECT b.project_id FROM project_expert_binding b JOIN expert_version e ON e.id=b.expert_version_id WHERE JSON_CONTAINS(e.mcp_version_ids,CAST(#{id} AS JSON),'$') UNION SELECT c.project_id FROM conversation c JOIN expert_version e ON e.id=c.selected_expert_version_id WHERE JSON_CONTAINS(e.mcp_version_ids,CAST(#{id} AS JSON),'$')) impacted ORDER BY impacted.project_id") List<Long> boundProjectsForVersion(Long id);
    @Select("SELECT expert_revision FROM codex_project WHERE id=#{id} FOR UPDATE") Long lockProject(Long id);
    @Update("UPDATE codex_project SET expert_revision=expert_revision+1 WHERE id=#{id}") int bumpProject(Long id);
}
