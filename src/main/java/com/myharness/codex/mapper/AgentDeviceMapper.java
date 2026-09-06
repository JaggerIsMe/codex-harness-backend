package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.entity.po.AgentWorkspaceRootPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentDeviceMapper {
    @Update("UPDATE agent_device SET project_experts=#{supported} WHERE id=#{id}")
    int expertCapability(@Param("id") Long id,@Param("supported") boolean supported);
    @Update("UPDATE agent_device SET conversation_attachments=#{supported} WHERE id=#{id}")
    int attachmentCapability(@Param("id") Long id,@Param("supported") boolean supported);
    @Update("UPDATE agent_workspace SET status='FAILED',failure_code='COMMAND_TIMEOUT',failure_message='目录准备超时，可重试' " +
            "WHERE status='CREATING' AND updated_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL #{timeoutSeconds} SECOND)")
    int failTimedOutWorkspaces(@Param("timeoutSeconds") long timeoutSeconds);

    @Update("UPDATE agent_workspace SET status='CREATING',failure_code=NULL,failure_message=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND device_id=#{deviceId} AND status='FAILED'")
    int retryProjectWorkspace(@Param("id") Long id,@Param("deviceId") Long deviceId);
    @org.apache.ibatis.annotations.Delete("DELETE FROM agent_event_message WHERE created_at<#{deadline} ORDER BY created_at LIMIT 1000")
    int deleteExpiredEvents(@Param("deadline") LocalDateTime deadline);
    @Insert("INSERT INTO agent_device(enrollment_id,device_code,device_name,token_hash,status,agent_version,os_name,os_version) " +
            "VALUES(#{enrollmentId},#{deviceCode},#{deviceName},#{tokenHash},#{status},#{agentVersion},#{osName},#{osVersion})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentDevicePO device);

    @Select("SELECT id,enrollment_id,device_code,device_name,token_hash,status,agent_version,os_name,os_version,isolation_mode,conversation_attachments,project_experts,last_heartbeat_at " +
            "FROM agent_device WHERE device_code=#{deviceCode}")
    AgentDevicePO selectByCode(@Param("deviceCode") String deviceCode);

    @Select("SELECT id,enrollment_id,device_code,device_name,token_hash,status,agent_version,os_name,os_version,isolation_mode,conversation_attachments,project_experts,last_heartbeat_at " +
            "FROM agent_device WHERE id=#{id}")
    AgentDevicePO selectById(@Param("id") Long id);

    @Select("SELECT id,enrollment_id,device_code,device_name,token_hash,status,agent_version,os_name,os_version,isolation_mode,conversation_attachments,project_experts,last_heartbeat_at " +
            "FROM agent_device ORDER BY created_at DESC")
    List<AgentDevicePO> selectAll();

    @Update("UPDATE agent_device SET status='ONLINE',last_heartbeat_at=#{now} WHERE id=#{id} AND status<>'DISABLED'")
    int markOnline(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_device SET status='OFFLINE' WHERE id=#{id} AND status='ONLINE'")
    int markOffline(@Param("id") Long id);

    @Update("UPDATE agent_device SET status='OFFLINE' WHERE status='ONLINE' AND (last_heartbeat_at IS NULL OR last_heartbeat_at<#{deadline})")
    int markTimedOutOffline(@Param("deadline") LocalDateTime deadline);

    @Select("SELECT id,enrollment_id,device_code,device_name,token_hash,status,agent_version,os_name,os_version,isolation_mode,conversation_attachments,project_experts,last_heartbeat_at " +
            "FROM agent_device WHERE status='ONLINE' AND (last_heartbeat_at IS NULL OR last_heartbeat_at<#{deadline})")
    List<AgentDevicePO> selectTimedOut(@Param("deadline") LocalDateTime deadline);

    @Update("UPDATE agent_device SET last_heartbeat_at=#{now},status='ONLINE' WHERE id=#{id} AND status<>'DISABLED'")
    int heartbeat(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_device SET status=#{status} WHERE id=#{id}")
    int updateStatus(@Param("id") Long id,@Param("status") String status);

    @Update("UPDATE agent_device SET device_name=#{deviceName},agent_version=#{agentVersion},os_name=#{osName},os_version=#{osVersion},isolation_mode=#{isolationMode}," +
            "last_heartbeat_at=#{now},status='ONLINE' WHERE id=#{id} AND status<>'DISABLED'")
    int updateRegistration(@Param("id") Long id, @Param("deviceName") String deviceName,
                           @Param("agentVersion") String agentVersion, @Param("osName") String osName,
                           @Param("osVersion") String osVersion,@Param("isolationMode") String isolationMode,
                           @Param("now") LocalDateTime now);

    @Insert("INSERT INTO agent_workspace(device_id,workspace_name,root_path,root_path_hash,status,last_reported_at) " +
            "VALUES(#{deviceId},#{workspaceName},#{rootPath},#{rootPathHash},'ENABLED',#{lastReportedAt}) " +
            "ON DUPLICATE KEY UPDATE root_path=VALUES(root_path),root_path_hash=VALUES(root_path_hash)," +
            "status=IF(status='DISABLED','DISABLED','ENABLED'),failure_code=NULL,failure_message=NULL,last_reported_at=VALUES(last_reported_at)")
    int upsertWorkspace(AgentWorkspacePO workspace);

    @Update("UPDATE agent_workspace SET status='MISSING' WHERE device_id=#{deviceId} AND status='ENABLED'")
    int markReportedWorkspacesMissing(@Param("deviceId") Long deviceId);

    @Insert("INSERT INTO agent_workspace(device_id,workspace_name,root_path,root_path_hash,status,parent_name,project_type," +
            "failure_code,failure_message,created_by,last_reported_at) VALUES(#{deviceId},#{workspaceName},NULL,NULL,'CREATING'," +
            "#{parentName},#{projectType},NULL,NULL,#{createdBy},#{lastReportedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCreatingWorkspace(AgentWorkspacePO workspace);

    @Update("UPDATE agent_workspace SET root_path=#{rootPath},root_path_hash=#{rootPathHash},status='ENABLED'," +
            "failure_code=NULL,failure_message=NULL,last_reported_at=#{lastReportedAt} WHERE id=#{id} AND device_id=#{deviceId} AND status<>'DISABLED'")
    int completeWorkspace(AgentWorkspacePO workspace);

    @Update("UPDATE agent_workspace SET status='FAILED',failure_code=#{failureCode},failure_message=#{failureMessage} " +
            "WHERE id=#{id} AND device_id=#{deviceId} AND status='CREATING'")
    int failWorkspace(AgentWorkspacePO workspace);

    @Update("UPDATE agent_workspace SET status='CREATING',failure_code=NULL,failure_message=NULL,created_by=#{createdBy} " +
            "WHERE id=#{id} AND device_id=#{deviceId} AND status IN ('CREATING','FAILED')")
    int retryWorkspace(AgentWorkspacePO workspace);

    @Select("SELECT id,device_id,workspace_name,root_path,root_path_hash,status,parent_name,project_type," +
            "failure_code,failure_message,created_by,last_reported_at " +
            "FROM agent_workspace WHERE device_id=#{deviceId} ORDER BY workspace_name")
    List<AgentWorkspacePO> selectWorkspaces(@Param("deviceId") Long deviceId);

    @Select("SELECT id,device_id,workspace_name,root_path,root_path_hash,status,parent_name,project_type," +
            "failure_code,failure_message,created_by,last_reported_at " +
            "FROM agent_workspace WHERE id=#{id} AND device_id=#{deviceId}")
    AgentWorkspacePO selectWorkspace(@Param("id") Long id, @Param("deviceId") Long deviceId);

    @Select("SELECT id,device_id,workspace_name,root_path,root_path_hash,status,parent_name,project_type," +
            "failure_code,failure_message,created_by,last_reported_at FROM agent_workspace " +
            "WHERE device_id=#{deviceId} AND workspace_name=#{workspaceName}")
    AgentWorkspacePO selectWorkspaceByName(@Param("deviceId") Long deviceId,
                                           @Param("workspaceName") String workspaceName);

    @Update("UPDATE agent_workspace_root SET status='DISABLED' WHERE device_id=#{deviceId}")
    int disableWorkspaceRoots(@Param("deviceId") Long deviceId);

    @Insert("INSERT INTO agent_workspace_root(device_id,root_name,status,last_reported_at) " +
            "VALUES(#{deviceId},#{rootName},'ENABLED',#{lastReportedAt}) ON DUPLICATE KEY UPDATE " +
            "status='ENABLED',last_reported_at=VALUES(last_reported_at)")
    int upsertWorkspaceRoot(AgentWorkspaceRootPO root);

    @Select("SELECT id,device_id,root_name,status,last_reported_at FROM agent_workspace_root " +
            "WHERE device_id=#{deviceId} AND status='ENABLED' ORDER BY root_name")
    List<AgentWorkspaceRootPO> selectWorkspaceRoots(@Param("deviceId") Long deviceId);

    @Select("SELECT id,device_id,root_name,status,last_reported_at FROM agent_workspace_root " +
            "WHERE device_id=#{deviceId} AND root_name=#{rootName} AND status='ENABLED'")
    AgentWorkspaceRootPO selectWorkspaceRoot(@Param("deviceId") Long deviceId, @Param("rootName") String rootName);

    @Insert("INSERT INTO agent_event_message(device_id,message_id,event_type,agent_timestamp) " +
            "VALUES(#{deviceId},#{messageId},#{eventType},#{timestamp}) " +
            "ON DUPLICATE KEY UPDATE duplicate_count=duplicate_count+1,last_seen_at=NOW(3)")
    int insertEvent(@Param("deviceId") Long deviceId, @Param("messageId") String messageId,
                    @Param("eventType") String eventType, @Param("timestamp") long timestamp);
}
