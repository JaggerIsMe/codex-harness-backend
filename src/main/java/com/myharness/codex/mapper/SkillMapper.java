package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.SkillDownloadPO;
import com.myharness.codex.entity.po.SkillDeploymentPO;
import com.myharness.codex.entity.po.SkillPO;
import com.myharness.codex.entity.po.SkillVersionPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SkillMapper {
    String SKILL_SELECT = "SELECT s.id,s.skill_name,s.description,s.status,s.created_by,s.created_at,s.updated_at," +
            "(SELECT COUNT(*) FROM skill_version sv WHERE sv.skill_id=s.id) version_count FROM skill s ";

    @Select(SKILL_SELECT + "WHERE (#{keyword} IS NULL OR #{keyword}='' OR s.skill_name LIKE CONCAT('%',#{keyword},'%') " +
            "OR s.description LIKE CONCAT('%',#{keyword},'%')) AND (#{status} IS NULL OR #{status}='' OR s.status=#{status}) " +
            "ORDER BY s.updated_at DESC,s.id DESC")
    List<SkillPO> selectSkills(@Param("keyword") String keyword,@Param("status") String status);

    @Select(SKILL_SELECT + "WHERE s.id=#{id}")
    SkillPO selectSkill(@Param("id") Long id);

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

    @Select("SELECT NULL id,#{deviceId} device_id,sv.id skill_version_id,sv.skill_id,s.skill_name,sv.version,sv.sha256," +
            "#{scopeType} scope_type,#{projectId} project_id,#{scopeKey} scope_key,NULL install_status,d.device_code,d.device_name " +
            "FROM skill_version sv JOIN skill s ON s.id=sv.skill_id " +
            "JOIN agent_device d ON d.id=#{deviceId} WHERE sv.id=#{versionId} AND sv.status='ACTIVE' " +
            "AND s.status='ENABLED' AND d.status<>'DISABLED'")
    SkillDeploymentPO selectDeployable(@Param("deviceId") Long deviceId,@Param("versionId") Long versionId,
                                       @Param("scopeType") String scopeType,@Param("projectId") Long projectId,
                                       @Param("scopeKey") String scopeKey);

    @Insert("INSERT INTO device_skill(device_id,skill_version_id,scope_type,project_id,scope_key,install_status,requested_at) " +
            "VALUES(#{deviceId},#{skillVersionId},#{scopeType},#{projectId},#{scopeKey},'INSTALLING',NOW(3)) " +
            "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),install_status='INSTALLING',error_message=NULL,requested_at=NOW(3)")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int upsertDeployment(SkillDeploymentPO deployment);

    @Select("SELECT ds.id,ds.device_id,ds.skill_version_id,sv.skill_id,s.skill_name,sv.version,sv.sha256,ds.scope_type,ds.project_id," +
            "p.project_name,w.workspace_name,ds.install_status,ds.error_message,ds.requested_at,ds.installed_at,ds.updated_at,d.device_code,d.device_name " +
            "FROM device_skill ds JOIN skill_version sv ON sv.id=ds.skill_version_id JOIN skill s ON s.id=sv.skill_id " +
            "JOIN agent_device d ON d.id=ds.device_id LEFT JOIN codex_project p ON p.id=ds.project_id " +
            "LEFT JOIN agent_workspace w ON w.id=p.workspace_id WHERE ds.id=#{id}")
    SkillDeploymentPO selectDeployment(@Param("id") Long id);

    @Select("SELECT ds.id,ds.device_id,ds.skill_version_id,sv.skill_id,s.skill_name,sv.version,sv.sha256,ds.scope_type,ds.project_id," +
            "p.project_name,w.workspace_name,ds.install_status,ds.error_message,ds.requested_at,ds.installed_at,ds.updated_at,d.device_code,d.device_name " +
            "FROM device_skill ds JOIN skill_version sv ON sv.id=ds.skill_version_id JOIN skill s ON s.id=sv.skill_id " +
            "JOIN agent_device d ON d.id=ds.device_id LEFT JOIN codex_project p ON p.id=ds.project_id " +
            "LEFT JOIN agent_workspace w ON w.id=p.workspace_id " +
            "WHERE (#{keyword} IS NULL OR #{keyword}='' OR s.skill_name LIKE CONCAT('%',#{keyword},'%') " +
            "OR d.device_name LIKE CONCAT('%',#{keyword},'%') OR p.project_name LIKE CONCAT('%',#{keyword},'%')) " +
            "AND (#{status} IS NULL OR #{status}='' OR ds.install_status=#{status}) " +
            "AND (#{scopeType} IS NULL OR #{scopeType}='' OR ds.scope_type=#{scopeType}) " +
            "AND (ds.scope_type='GLOBAL' OR p.user_id=#{userId}) " +
            "ORDER BY ds.updated_at DESC,ds.id DESC")
    List<SkillDeploymentPO> selectDeployments(@Param("keyword") String keyword,@Param("status") String status,
                                               @Param("scopeType") String scopeType,@Param("userId") Long userId);

    @Select("SELECT sv.id version_id,sv.storage_path,sv.sha256,sv.file_size,sv.status version_status," +
            "(SELECT ds.install_status FROM device_skill ds WHERE ds.skill_version_id=sv.id AND ds.device_id=#{deviceId} " +
            "AND ds.install_status<>'REMOVED' ORDER BY ds.updated_at DESC LIMIT 1) install_status " +
            "FROM skill_version sv WHERE sv.id=#{versionId} AND EXISTS (SELECT 1 FROM device_skill ds " +
            "WHERE ds.skill_version_id=sv.id AND ds.device_id=#{deviceId} AND ds.install_status<>'REMOVED')")
    SkillDownloadPO selectDownload(@Param("versionId") Long versionId,@Param("deviceId") Long deviceId);

    @Update("UPDATE device_skill SET install_status=#{status},error_message=#{error}," +
            "installed_at=CASE WHEN #{status}='INSTALLED' THEN #{now} ELSE installed_at END " +
            "WHERE id=#{deviceSkillId} AND device_id=#{deviceId}")
    int updateDeployment(@Param("deviceSkillId") Long deviceSkillId,@Param("deviceId") Long deviceId,
                         @Param("status") String status,@Param("error") String error,@Param("now") LocalDateTime now);

    @Update("UPDATE device_skill SET install_status='REMOVING',error_message=NULL,requested_at=#{now} WHERE id=#{id}")
    int markRemoving(@Param("id") Long id,@Param("now") LocalDateTime now);

    @Update("UPDATE device_skill SET install_status='FAILED',error_message='Agent command timed out' " +
            "WHERE install_status IN ('INSTALLING','REMOVING') AND requested_at<#{deadline}")
    int failTimedOutDeployments(@Param("deadline") LocalDateTime deadline);
}
