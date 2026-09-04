package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.ApprovalRequestPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ApprovalMapper {
    @Insert("INSERT IGNORE INTO approval_request(conversation_id,turn_id,device_id,remote_request_id,approval_type,payload,status) " +
            "VALUES(#{conversationId},#{turnId},#{deviceId},#{remoteRequestId},#{approvalType},#{payload},'PENDING')")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(ApprovalRequestPO approval);

    @Select("SELECT a.id,a.conversation_id,a.turn_id,a.device_id,a.remote_request_id,a.approval_type,a.payload,a.status,a.decided_by,d.device_code " +
            "FROM approval_request a JOIN agent_device d ON d.id=a.device_id WHERE a.id=#{id}")
    ApprovalRequestPO selectById(@Param("id") Long id);

    @Select("SELECT a.id,a.conversation_id,a.turn_id,a.device_id,a.remote_request_id,a.approval_type,a.payload,a.status,a.decided_by,d.device_code " +
            "FROM approval_request a JOIN agent_device d ON d.id=a.device_id JOIN conversation c ON c.id=a.conversation_id " +
            "WHERE a.id=#{id} AND c.user_id=#{userId}")
    ApprovalRequestPO selectOwnedById(@Param("id") Long id,@Param("userId") Long userId);

    @Select("SELECT a.id,a.conversation_id,a.turn_id,a.device_id,a.remote_request_id,a.approval_type,a.payload,a.status,a.decided_by,d.device_code " +
            "FROM approval_request a JOIN agent_device d ON d.id=a.device_id WHERE a.device_id=#{deviceId} AND a.remote_request_id=#{requestId}")
    ApprovalRequestPO selectRemote(@Param("deviceId") Long deviceId,@Param("requestId") String requestId);

    @Select("SELECT a.id,a.conversation_id,a.turn_id,a.device_id,a.remote_request_id,a.approval_type,a.payload,a.status,a.decided_by,d.device_code " +
            "FROM approval_request a JOIN agent_device d ON d.id=a.device_id WHERE a.conversation_id=#{conversationId} ORDER BY a.created_at")
    List<ApprovalRequestPO> selectByConversation(@Param("conversationId") Long conversationId);

    @Update("UPDATE approval_request SET status=#{status},decided_by=#{decidedBy},decided_at=#{now} WHERE id=#{id} AND status='PENDING'")
    int decide(@Param("id") Long id,@Param("status") String status,@Param("decidedBy") Long decidedBy,
               @Param("now") LocalDateTime now);
}
