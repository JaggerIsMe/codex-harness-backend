package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.SkillDownloadPO;
import com.myharness.codex.entity.po.SkillPO;
import com.myharness.codex.entity.po.SkillVersionPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface SkillMapper {
    // Acquired before Skill/expert/project locks by every catalog writer.
    @Select("SELECT id FROM skill_catalog_lock WHERE id=1 FOR UPDATE")
    Integer lockCatalog();
    String SKILL_SELECT = "SELECT s.id,s.skill_name,s.description,s.status,s.created_by,s.created_at,s.updated_at," +
            "(SELECT COUNT(*) FROM skill_version sv WHERE sv.skill_id=s.id) version_count FROM skill s ";

    @Select(SKILL_SELECT + "WHERE (#{keyword} IS NULL OR #{keyword}='' OR s.skill_name LIKE CONCAT('%',#{keyword},'%') " +
            "OR s.description LIKE CONCAT('%',#{keyword},'%')) AND (#{status} IS NULL OR #{status}='' OR s.status=#{status}) " +
            "ORDER BY s.updated_at DESC,s.id DESC")
    List<SkillPO> selectSkills(@Param("keyword") String keyword,@Param("status") String status);

    @Select(SKILL_SELECT + "WHERE s.id=#{id}")
    SkillPO selectSkill(@Param("id") Long id);

    @Select(SKILL_SELECT + "WHERE s.id=#{id} FOR UPDATE")
    SkillPO lockSkill(@Param("id") Long id);

    @Select(SKILL_SELECT + "WHERE s.skill_name=#{skillName}")
    SkillPO selectSkillByName(@Param("skillName") String skillName);

    @Insert("INSERT INTO skill(skill_name,description,status,created_by) VALUES(#{skillName},#{description},'ENABLED',#{createdBy})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertSkill(SkillPO skill);

    @Update("UPDATE skill SET skill_name=#{skillName},description=#{description},status=#{status} WHERE id=#{id}")
    int updateSkill(SkillPO skill);

    @Insert("INSERT INTO skill_version(skill_id,version,storage_path,sha256,file_size,status,created_by) " +
            "VALUES(#{skillId},#{version},#{storagePath},#{sha256},#{fileSize},'ACTIVE',#{createdBy})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertVersion(SkillVersionPO version);

    @Select("SELECT id,skill_id,version,storage_path,sha256,file_size,status,created_by,created_at,updated_at FROM skill_version WHERE id=#{id}")
    SkillVersionPO selectVersion(@Param("id") Long id);

    @Select("SELECT id,skill_id,version,storage_path,sha256,file_size,status,created_by,created_at,updated_at " +
            "FROM skill_version WHERE skill_id=#{skillId} ORDER BY created_at DESC,id DESC")
    List<SkillVersionPO> selectVersions(@Param("skillId") Long skillId);

    @Update("UPDATE skill_version SET status=#{status} WHERE id=#{id}")
    int updateVersionStatus(@Param("id") Long id,@Param("status") String status);

    @Update("UPDATE skill_version SET status='DISABLED' WHERE skill_id=#{skillId} AND status='ACTIVE'")
    int disableActiveVersions(@Param("skillId") Long skillId);

    @Select("SELECT sv.id version_id,sv.storage_path,sv.sha256,sv.file_size,sv.status version_status FROM skill_version sv " +
            "JOIN skill s ON s.id=sv.skill_id WHERE sv.id=#{versionId} AND sv.status='ACTIVE' AND s.status='ENABLED' AND EXISTS (" +
            "SELECT 1 FROM codex_project p JOIN agent_workspace w ON w.id=p.workspace_id " +
            "JOIN project_expert_binding b ON b.project_id=p.id JOIN expert e ON e.id=b.expert_id " +
            "JOIN user_expert_assignment a ON a.expert_id=e.id AND a.user_id=p.user_id AND a.status='ENABLED' " +
            "JOIN sys_user u ON u.id=p.user_id AND u.status='ENABLED' " +
            "JOIN user_device_assignment da ON da.user_id=p.user_id AND da.device_id=p.device_id AND da.status='ENABLED' " +
            "JOIN expert_version v ON v.expert_id=e.id WHERE p.device_id=#{deviceId} AND p.status='ACTIVE' " +
            "AND w.status='ENABLED' AND e.status='PUBLISHED' AND JSON_CONTAINS(v.skill_version_ids,CAST(sv.id AS JSON),'$') " +
            "AND (v.id=b.expert_version_id OR EXISTS (SELECT 1 FROM conversation c WHERE c.project_id=p.id " +
            "AND c.selected_expert_id=e.id AND c.selected_expert_version_id=v.id)))")
    SkillDownloadPO selectDownload(@Param("versionId") Long versionId,@Param("deviceId") Long deviceId);
}
