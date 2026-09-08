package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ModelConfigurationMapper {
    @Select("SELECT c.*,v.version_no current_version_no,v.runtime_spec,v.config_digest FROM model_configuration c LEFT JOIN model_configuration_version v ON v.id=c.current_version_id WHERE (#{keyword}='' OR c.name LIKE CONCAT('%',#{keyword},'%') OR c.configuration_code LIKE CONCAT('%',#{keyword},'%')) AND (#{status}='' OR c.status=#{status}) ORDER BY c.id DESC LIMIT 200") List<ModelConfigurationPO> list(@Param("keyword") String keyword,@Param("status") String status);
    @Select("SELECT c.*,v.version_no current_version_no,v.runtime_spec,v.config_digest FROM model_configuration c LEFT JOIN model_configuration_version v ON v.id=c.current_version_id WHERE c.id=#{id}") ModelConfigurationPO get(Long id);
    @Select("SELECT * FROM model_configuration WHERE id=#{id} FOR UPDATE") ModelConfigurationPO lock(Long id);
    @Select("SELECT COUNT(*) FROM model_configuration WHERE configuration_code=#{code} AND (#{excludeId} IS NULL OR id<>#{excludeId})") int codeExists(@Param("code") String code,@Param("excludeId") Long excludeId);
    @Insert("INSERT INTO model_configuration(configuration_code,name,description,status,created_by) VALUES(#{configurationCode},#{name},#{description},'ENABLED',#{createdBy})") @Options(useGeneratedKeys=true,keyProperty="id") int insert(ModelConfigurationPO value);
    @Select("SELECT COALESCE(MAX(version_no),0)+1 FROM model_configuration_version WHERE model_configuration_id=#{id}") long nextVersion(Long id);
    @Insert("INSERT INTO model_configuration_version(model_configuration_id,version_no,configuration_code,name,description,runtime_spec,encrypted_api_key,config_digest) VALUES(#{modelConfigurationId},#{versionNo},#{configurationCode},#{name},#{description},#{runtimeSpec},#{encryptedApiKey},#{configDigest})") @Options(useGeneratedKeys=true,keyProperty="id") int insertVersion(ModelConfigurationVersionPO value);
    @Update("UPDATE model_configuration SET configuration_code=#{configurationCode},name=#{name},description=#{description},current_version_id=#{currentVersionId},revision=revision+1 WHERE id=#{id}") int update(ModelConfigurationPO value);
    @Update("UPDATE model_configuration SET current_version_id=#{versionId} WHERE id=#{id}") int setInitialVersion(@Param("id") Long id,@Param("versionId") Long versionId);
    @Update("UPDATE model_configuration SET status=#{status},revision=revision+1 WHERE id=#{id}") int status(@Param("id") Long id,@Param("status") String status);
    @Select("SELECT v.*,c.status configuration_status FROM model_configuration_version v JOIN model_configuration c ON c.id=v.model_configuration_id WHERE v.id=#{id}") ModelConfigurationVersionPO version(Long id);
    @Select("SELECT v.*,c.status configuration_status FROM model_configuration_version v JOIN model_configuration c ON c.id=v.model_configuration_id WHERE v.model_configuration_id=#{id} ORDER BY v.version_no DESC") List<ModelConfigurationVersionPO> versions(Long id);
    @Select("SELECT v.*,c.status configuration_status FROM model_configuration_version v JOIN model_configuration c ON c.id=v.model_configuration_id WHERE c.status='ENABLED' AND v.status='ACTIVE' ORDER BY c.name,v.version_no DESC") List<ModelConfigurationVersionPO> selectableVersions();
    @Update("UPDATE model_configuration_version SET status='REVOKED' WHERE id=#{versionId} AND model_configuration_id=#{configurationId} AND status='ACTIVE'") int revoke(@Param("configurationId") Long configurationId,@Param("versionId") Long versionId);
    @Select("SELECT a.*,v.model_configuration_id configuration_id,v.version_no,v.configuration_code,v.name,JSON_UNQUOTE(JSON_EXTRACT(v.runtime_spec,'$.modelId')) model_id FROM device_model_assignment a LEFT JOIN model_configuration_version v ON v.id=a.model_configuration_version_id WHERE a.device_id=#{deviceId}") DeviceModelAssignmentPO assignment(Long deviceId);
    @Select("SELECT * FROM device_model_assignment WHERE device_id=#{deviceId} FOR UPDATE") DeviceModelAssignmentPO lockAssignment(Long deviceId);
    @Insert("INSERT INTO device_model_assignment(device_id,runtime_mode,model_configuration_version_id,revision,assigned_by) VALUES(#{deviceId},#{runtimeMode},#{versionId},0,#{userId}) ON DUPLICATE KEY UPDATE runtime_mode=VALUES(runtime_mode),model_configuration_version_id=VALUES(model_configuration_version_id),revision=revision+1,assigned_by=VALUES(assigned_by)") int assign(@Param("deviceId") Long deviceId,@Param("runtimeMode") String runtimeMode,@Param("versionId") Long versionId,@Param("userId") Long userId);
    @Delete("DELETE FROM device_model_assignment WHERE device_id=#{deviceId}") int unassign(Long deviceId);
}
