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
            "SELECT #{conversationId},#{turnId},#{deviceId},#{remoteRequestId},#{approvalType},#{payload},'PENDING' " +
            "FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id " +
            "WHERE t.id=#{turnId} AND t.conversation_id=#{conversationId} AND c.device_id=#{deviceId} " +
            "AND t.status IN ('RUNNING','WAITING_APPROVAL')")
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
            "FROM approval_request a JOIN agent_device d ON d.id=a.device_id WHERE a.device_id=#{deviceId} AND a.remote_request_id=#{requestId} FOR UPDATE")
    ApprovalRequestPO selectRemote(@Param("deviceId") Long deviceId,@Param("requestId") String requestId);

    @Select("SELECT a.id,a.conversation_id,a.turn_id,a.device_id,a.remote_request_id,a.approval_type,a.payload,a.status,a.decided_by,d.device_code " +
            "FROM approval_request a JOIN agent_device d ON d.id=a.device_id WHERE a.conversation_id=#{conversationId} ORDER BY a.created_at")
    List<ApprovalRequestPO> selectByConversation(@Param("conversationId") Long conversationId);

    @Update("UPDATE approval_request a JOIN conversation_turn t ON t.id=a.turn_id " +
            "SET a.status=#{status},a.decided_by=#{decidedBy},a.decided_at=#{now} WHERE a.id=#{id} AND a.status='PENDING' " +
            "AND t.status IN ('RUNNING','WAITING_APPROVAL')")
    int decide(@Param("id") Long id,@Param("status") String status,@Param("decidedBy") Long decidedBy,
               @Param("now") LocalDateTime now);

    @Update("UPDATE approval_request SET decision_message_id=#{messageId} WHERE id=#{id}")
    int recordDispatch(@Param("id") Long id,@Param("messageId") String messageId);

    @Update("UPDATE approval_request SET decision_message_id=NULL WHERE id=#{id} AND decision_message_id=#{messageId}")
    int acknowledgeDispatch(@Param("id") Long id,@Param("messageId") String messageId);

    @Update("UPDATE approval_request a JOIN conversation_turn t ON t.id=a.turn_id SET a.status='PENDING',a.decided_by=NULL,a.decided_at=NULL,a.decision_message_id=NULL " +
            "WHERE a.id=#{id} AND a.device_id=#{deviceId} AND a.decision_message_id=#{messageId} " +
            "AND a.status IN ('APPROVED','REJECTED','CANCELLED') AND t.status IN ('RUNNING','WAITING_APPROVAL')")
    int rejectDispatch(@Param("id") Long id,@Param("deviceId") Long deviceId,@Param("messageId") String messageId);

    @Select("SELECT COUNT(*) FROM approval_request WHERE turn_id=#{turnId} AND (status='PENDING' OR decision_message_id IS NOT NULL)")
    int pendingForTurn(Long turnId);

    @Update("UPDATE approval_request SET status='CANCELLED',decided_at=#{now},decision_message_id=NULL " +
            "WHERE turn_id=#{turnId} AND device_id=#{deviceId} AND (status='PENDING' OR decision_message_id IS NOT NULL)")
    int cancelTurn(@Param("turnId") Long turnId,@Param("deviceId") Long deviceId,@Param("now") LocalDateTime now);

    @Update("UPDATE approval_request a JOIN conversation_turn t ON t.id=a.turn_id " +
            "SET a.status='CANCELLED',a.decided_at=#{now},a.decision_message_id=NULL WHERE a.conversation_id=#{conversationId} " +
            "AND (a.status='PENDING' OR a.decision_message_id IS NOT NULL) AND t.status NOT IN ('CREATED','RUNNING','WAITING_APPROVAL')")
    int cancelInactive(@Param("conversationId") Long conversationId,@Param("now") LocalDateTime now);
}
