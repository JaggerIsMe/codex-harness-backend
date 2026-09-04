package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ConversationTurnPO;
import com.myharness.codex.entity.po.ConversationMessagePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationMapper {
    // Only empty conversations or known pre-generation failures may replace an unpersisted Codex thread.
    String RECREATABLE_THREAD = "NOT EXISTS(SELECT 1 FROM conversation_turn t WHERE t.conversation_id=#{id} AND t.id<>#{turnId} " +
            "AND (t.codex_turn_id IS NOT NULL OR t.status<>'FAILED' OR t.failure_code IS NULL OR t.failure_code<>'COMMAND_FAILED' " +
            "OR t.failure_message IS NULL OR NOT (t.failure_message LIKE 'Codex method failed: turn/start: failed to load configuration:%' " +
            "OR t.failure_message LIKE 'Codex method failed: thread/read: thread not loaded:%' " +
            "OR t.failure_message LIKE 'Codex thread is not loaded or persisted:%'))) " +
            "AND NOT EXISTS(SELECT 1 FROM conversation_message m WHERE m.conversation_id=#{id} AND m.role<>'USER')";
    @Select("SELECT " + RECREATABLE_THREAD)
    boolean canRecreateUnstartedThread(@Param("id") Long id, @Param("turnId") Long turnId);

    @Update("UPDATE conversation SET codex_thread_id=#{next},last_activity_at=#{now} WHERE id=#{id} AND device_id=#{deviceId} " +
            "AND codex_thread_id=#{previous} AND status='ACTIVE' " +
            "AND EXISTS(SELECT 1 FROM conversation_turn active WHERE active.id=#{turnId} AND active.conversation_id=#{id} AND active.status='CREATED') AND " + RECREATABLE_THREAD)
    int replaceUnstartedThread(@Param("id") Long id,@Param("deviceId") Long deviceId,@Param("turnId") Long turnId,
            @Param("previous") String previous,@Param("next") String next,@Param("now") LocalDateTime now);
    @Select("SELECT t.id turn_id,c.id conversation_id,c.user_id,c.device_id,d.device_code FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id JOIN agent_device d ON d.id=c.device_id WHERE t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')")
    List<com.myharness.codex.entity.vo.RevokedTurnVO> selectRunningWork();
    @Insert("INSERT INTO conversation(user_id,device_id,workspace_id,project_id,title,status,last_activity_at) " +
            "VALUES(#{userId},#{deviceId},#{workspaceId},#{projectId},#{title},#{status},#{lastActivityAt})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertConversation(ConversationPO conversation);

    @Select("SELECT c.id,c.user_id,c.device_id,c.workspace_id,c.project_id,c.title,c.codex_thread_id,c.status,c.last_activity_at," +
            "d.device_code,w.workspace_name,p.project_name FROM conversation c JOIN agent_device d ON d.id=c.device_id " +
            "JOIN agent_workspace w ON w.id=c.workspace_id JOIN codex_project p ON p.id=c.project_id WHERE c.id=#{id}")
    ConversationPO selectConversation(@Param("id") Long id);

    @Select("SELECT c.id,c.user_id,c.device_id,c.workspace_id,c.project_id,c.title,c.codex_thread_id,c.status,c.last_activity_at," +
            "d.device_code,w.workspace_name,p.project_name FROM conversation c JOIN agent_device d ON d.id=c.device_id " +
            "JOIN agent_workspace w ON w.id=c.workspace_id JOIN codex_project p ON p.id=c.project_id " +
            "WHERE c.user_id=#{userId} AND c.project_id=#{projectId} " +
            "ORDER BY c.last_activity_at DESC,c.id DESC LIMIT 100")
    List<ConversationPO> selectProjectConversations(@Param("projectId") Long projectId,@Param("userId") Long userId);

    @Select("SELECT c.id,c.user_id,c.device_id,c.workspace_id,c.project_id,c.title,c.codex_thread_id,c.status,c.last_activity_at," +
            "d.device_code,w.workspace_name,p.project_name FROM conversation c JOIN agent_device d ON d.id=c.device_id " +
            "JOIN agent_workspace w ON w.id=c.workspace_id JOIN codex_project p ON p.id=c.project_id " +
            "WHERE c.id=#{id} AND c.project_id=#{projectId} AND c.user_id=#{userId}")
    ConversationPO selectOwnedConversation(@Param("projectId") Long projectId,@Param("id") Long id,@Param("userId") Long userId);

    @Select("SELECT c.id,c.user_id,c.device_id,c.workspace_id,c.project_id,c.title,c.codex_thread_id,c.status,c.last_activity_at," +
            "d.device_code,w.workspace_name,p.project_name FROM conversation c JOIN agent_device d ON d.id=c.device_id " +
            "JOIN agent_workspace w ON w.id=c.workspace_id JOIN codex_project p ON p.id=c.project_id WHERE c.id=#{id} FOR UPDATE")
    ConversationPO lockConversation(@Param("id") Long id);

    @Update("UPDATE conversation SET codex_thread_id=#{codexThreadId},last_activity_at=#{now} " +
            "WHERE id=#{id} AND device_id=#{deviceId} AND codex_thread_id IS NULL AND status='ACTIVE'")
    int setThreadStarted(@Param("id") Long id,@Param("deviceId") Long deviceId,
                         @Param("codexThreadId") String codexThreadId,@Param("now") LocalDateTime now);

    @Update("UPDATE conversation SET status='FAILED',last_activity_at=#{now} WHERE id=#{id} AND device_id=#{deviceId}")
    int failConversation(@Param("id") Long id,@Param("deviceId") Long deviceId,@Param("now") LocalDateTime now);

    @Insert("INSERT INTO conversation_turn(conversation_id,status) VALUES(#{conversationId},'CREATED')")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertTurn(ConversationTurnPO turn);

    @Select("SELECT id,conversation_id,codex_turn_id,status FROM conversation_turn WHERE id=#{id}")
    ConversationTurnPO selectTurn(@Param("id") Long id);

    @Select("SELECT id,conversation_id,codex_turn_id,status FROM conversation_turn " +
            "WHERE conversation_id=#{conversationId} AND status IN ('CREATED','RUNNING','WAITING_APPROVAL') " +
            "ORDER BY id DESC LIMIT 1")
    ConversationTurnPO selectActiveTurn(@Param("conversationId") Long conversationId);

    @Update("UPDATE conversation_turn t JOIN conversation c ON c.id=t.conversation_id " +
            "SET t.codex_turn_id=#{codexTurnId},t.status='RUNNING',t.started_at=#{now},c.last_activity_at=#{now} " +
            "WHERE t.id=#{turnId} AND t.conversation_id=#{conversationId} AND c.device_id=#{deviceId} AND t.status='CREATED'")
    int setTurnStarted(@Param("turnId") Long turnId,@Param("conversationId") Long conversationId,
                       @Param("deviceId") Long deviceId,@Param("codexTurnId") String codexTurnId,
                       @Param("now") LocalDateTime now);

    @Update("UPDATE conversation_turn SET status='WAITING_APPROVAL' WHERE id=#{turnId} AND conversation_id=#{conversationId} AND status='RUNNING'")
    int waitApproval(@Param("turnId") Long turnId,@Param("conversationId") Long conversationId);

    @Update("UPDATE conversation_turn SET status='RUNNING' WHERE id=#{turnId} AND conversation_id=#{conversationId} AND status='WAITING_APPROVAL'")
    int resumeTurn(@Param("turnId") Long turnId,@Param("conversationId") Long conversationId);

    @Update("UPDATE conversation_turn t JOIN conversation c ON c.id=t.conversation_id " +
            "SET t.status=#{status},t.failure_code=#{failureCode},t.failure_message=#{failureMessage},t.finished_at=#{now},c.last_activity_at=#{now} " +
            "WHERE t.id=#{turnId} AND t.conversation_id=#{conversationId} AND c.device_id=#{deviceId} " +
            "AND t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')")
    int finishTurn(@Param("turnId") Long turnId,@Param("conversationId") Long conversationId,
                   @Param("deviceId") Long deviceId,@Param("status") String status,
                   @Param("failureCode") String failureCode,@Param("failureMessage") String failureMessage,
                   @Param("now") LocalDateTime now);

    @Update("UPDATE conversation_turn t JOIN conversation c ON c.id=t.conversation_id " +
            "SET t.status='FAILED',t.failure_code='AGENT_DISCONNECTED',t.failure_message='Agent connection was lost',t.finished_at=#{now} " +
            "WHERE c.device_id=#{deviceId} AND t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')")
    int failActiveTurnsForDevice(@Param("deviceId") Long deviceId,@Param("now") LocalDateTime now);

    @Update("UPDATE conversation SET status='FAILED' WHERE status='ACTIVE' AND codex_thread_id IS NULL AND created_at<#{deadline}")
    int failTimedOutThreadStarts(@Param("deadline") LocalDateTime deadline);

    @Update("UPDATE conversation_turn SET status='FAILED',failure_code='COMMAND_TIMEOUT',failure_message='Agent command timed out',finished_at=#{now} " +
            "WHERE status='CREATED' AND created_at<#{deadline}")
    int failTimedOutTurnStarts(@Param("deadline") LocalDateTime deadline,@Param("now") LocalDateTime now);

    @Select("SELECT COALESCE(MAX(sequence_no),0)+1 FROM conversation_message WHERE conversation_id=#{conversationId}")
    long nextSequence(@Param("conversationId") Long conversationId);

    @Insert("INSERT INTO conversation_message(conversation_id,turn_id,sequence_no,role,message_type,content) " +
            "VALUES(#{conversationId},#{turnId},#{sequence},#{role},#{messageType},#{content})")
    int insertMessage(@Param("conversationId") Long conversationId,@Param("turnId") Long turnId,
                      @Param("sequence") long sequence,@Param("role") String role,
                      @Param("messageType") String messageType,@Param("content") String content);

    @Select("SELECT * " +
            "FROM conversation_message WHERE conversation_id=#{conversationId} ORDER BY sequence_no")
    List<ConversationMessagePO> selectMessages(@Param("conversationId") Long conversationId);

    @Select("SELECT * FROM conversation_message WHERE conversation_id=#{conversationId} AND message_key=#{key}")
    ConversationMessagePO selectLogicalMessage(@Param("conversationId") Long conversationId,@Param("key") String key);

    @Insert("INSERT INTO conversation_message(conversation_id,turn_id,sequence_no,role,message_type,content,message_key,item_id,phase,status,revision,created_at) " +
            "VALUES(#{conversationId},#{turnId},#{sequenceNo},#{role},#{messageType},'',#{messageKey},#{itemId},#{phase},'STREAMING',0,#{createdAt})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertLogicalMessage(ConversationMessagePO message);

    @Update("UPDATE conversation_message SET content=#{content},message_type=#{messageType},phase=#{phase},status=#{status}," +
            "metadata=#{metadata},truncated=#{truncated},revision=#{revision},updated_at=#{updatedAt},completed_at=#{completedAt} " +
            "WHERE id=#{id} AND conversation_id=#{conversationId} AND message_key=#{messageKey} AND revision<=#{revision}")
    int saveLogicalMessage(ConversationMessagePO message);

    @Select("SELECT * FROM conversation_message WHERE conversation_id=#{conversationId} AND (#{before}=0 OR sequence_no<#{before}) " +
            "ORDER BY sequence_no DESC LIMIT #{limit}")
    List<ConversationMessagePO> selectMessagePage(@Param("conversationId") Long conversationId,@Param("before") long before,@Param("limit") int limit);

    @Select("SELECT * FROM conversation_message WHERE turn_id=#{turnId} AND message_key IS NOT NULL ORDER BY sequence_no")
    List<ConversationMessagePO> selectTurnMessages(@Param("turnId") Long turnId);

    @Select("SELECT id,conversation_id,codex_turn_id,status FROM conversation_turn WHERE conversation_id=#{conversationId} ORDER BY id DESC LIMIT 1")
    ConversationTurnPO selectLatestTurn(@Param("conversationId") Long conversationId);

    @Select("SELECT t.id,t.conversation_id,t.status FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id " +
            "WHERE c.device_id=#{deviceId} AND t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')")
    List<ConversationTurnPO> selectActiveTurnsForDevice(@Param("deviceId") Long deviceId);

    @Select("SELECT DISTINCT user_id FROM conversation WHERE device_id=#{deviceId}")
    List<Long> selectOwnerIdsForDevice(@Param("deviceId") Long deviceId);
}
