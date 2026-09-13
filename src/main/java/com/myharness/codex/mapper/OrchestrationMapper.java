package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface OrchestrationMapper {
    String ACTIVE="'QUEUED','RUNNING','CANCELING','NEEDS_ATTENTION'";
    @Insert("INSERT INTO orchestration_execution(project_id,user_id,device_id,title,goal,request_key,request_hash,plan_json) VALUES(#{projectId},#{userId},#{deviceId},#{title},#{goal},#{requestKey},#{requestHash},#{planJson})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(OrchestrationExecutionPO p);
    @Insert("INSERT INTO orchestration_step(execution_id,position,name,expert_id,objective) VALUES(#{executionId},#{position},#{name},#{expertId},#{objective})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertStep(OrchestrationStepPO p);
    @Select("SELECT * FROM orchestration_execution WHERE id=#{id}")
    OrchestrationExecutionPO get(Long id);
    @Select("SELECT * FROM orchestration_execution WHERE user_id=#{userId} AND request_key=#{key}")
    OrchestrationExecutionPO byRequest(@Param("userId") Long userId,@Param("key") String key);
    @Select("SELECT COUNT(*) FROM orchestration_execution WHERE user_id=#{userId} AND status IN ("+ACTIVE+")")
    int pendingForUser(Long userId);
    @Select("SELECT * FROM orchestration_execution WHERE project_id=#{projectId} AND user_id=#{userId} AND (#{keyword}='' OR LOCATE(#{keyword},title)>0) ORDER BY id DESC LIMIT 100")
    List<OrchestrationExecutionPO> list(@Param("projectId") Long projectId,@Param("userId") Long userId,@Param("keyword") String keyword);
    @Select("SELECT * FROM orchestration_execution WHERE status IN ("+ACTIVE+") ORDER BY updated_at,id LIMIT 100")
    List<OrchestrationExecutionPO> scheduled();
    @Select("SELECT * FROM orchestration_step WHERE execution_id=#{id} ORDER BY position")
    List<OrchestrationStepPO> steps(Long id);
    @Select("SELECT * FROM orchestration_step WHERE id=#{id}")
    OrchestrationStepPO step(Long id);
    @Update("UPDATE orchestration_execution SET status=#{status},cancel_requested=IF(#{status}='CANCELING',TRUE,cancel_requested),failure_message=#{reason},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id}")
    int status(@Param("id") Long id,@Param("status") String status,@Param("reason") String reason);
    @Update("UPDATE orchestration_execution SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id}")
    int touch(Long id);
    @Update("UPDATE orchestration_step SET status=#{status},failure_message=#{reason},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id}")
    int stepStatus(@Param("id") Long id,@Param("status") String status,@Param("reason") String reason);
    @Update("UPDATE orchestration_step SET status=#{next},input_snapshot=COALESCE(#{input},input_snapshot),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status=#{previous}")
    int claim(@Param("id") Long id,@Param("previous") String previous,@Param("next") String next,@Param("input") String input);
    @Update("UPDATE orchestration_step s JOIN orchestration_execution e ON e.id=s.execution_id SET s.conversation_id=#{cid},s.status='WAITING_THREAD',s.updated_at=CURRENT_TIMESTAMP(6) WHERE s.id=#{id} AND s.status='CREATING' AND s.conversation_id IS NULL AND e.project_id=#{projectId} AND e.user_id=#{userId} AND e.status='RUNNING'")
    int linkConversation(@Param("id") Long id,@Param("cid") Long cid,@Param("projectId") Long projectId,@Param("userId") Long userId);
    @Update("UPDATE orchestration_step s JOIN orchestration_execution e ON e.id=s.execution_id SET s.turn_id=#{tid},s.status='RUNNING',s.updated_at=CURRENT_TIMESTAMP(6) WHERE s.id=#{id} AND s.conversation_id=#{cid} AND s.status='DISPATCHING' AND s.turn_id IS NULL AND e.project_id=#{projectId} AND e.user_id=#{userId} AND e.status='RUNNING'")
    int linkTurn(@Param("id") Long id,@Param("tid") Long tid,@Param("cid") Long cid,@Param("projectId") Long projectId,@Param("userId") Long userId);
    @Update("UPDATE orchestration_step s JOIN conversation c ON c.id=s.conversation_id SET s.terminal_status=#{status} WHERE s.turn_id=#{turnId} AND c.device_id=#{deviceId} AND c.id=#{conversationId} AND s.terminal_status IS NULL")
    int terminal(@Param("turnId") Long turnId,@Param("conversationId") Long conversationId,@Param("deviceId") Long deviceId,@Param("status") String status);
    @Update("UPDATE orchestration_step SET status='SUCCEEDED',result_json=#{result},failure_message=NULL WHERE id=#{id}")
    int result(@Param("id") Long id,@Param("result") String result);
    @Select("SELECT * FROM conversation_message WHERE turn_id=#{turnId} AND role='ASSISTANT' AND message_type='TEXT' ORDER BY sequence_no DESC LIMIT 200")
    List<ConversationMessagePO> messages(Long turnId);
    @Select("SELECT COUNT(*) FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id WHERE c.project_id=#{id} AND t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')")
    int activeProjectTurns(Long id);
    @Select("SELECT COUNT(*) FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id WHERE c.device_id=#{id} AND t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')")
    int activeDeviceTurns(Long id);
    @Select("SELECT COUNT(*) FROM orchestration_execution WHERE project_id=#{projectId} AND id<>#{id} AND status IN ('RUNNING','CANCELING','NEEDS_ATTENTION')")
    int otherActive(@Param("projectId") Long projectId,@Param("id") Long id);
    @Select("SELECT COUNT(*) FROM orchestration_step WHERE conversation_id=#{id}")
    int managedConversation(Long id);
    @Select("<script>SELECT DISTINCT conversation_id FROM orchestration_step WHERE conversation_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> managedConversations(@Param("ids") List<Long> ids);
    String ORDINARY_FILTER=ConversationMapper.PROJECT_CONVERSATION_FILTER+
            "AND NOT EXISTS(SELECT 1 FROM orchestration_step s WHERE s.conversation_id=c.id) ";
    @Select(ConversationMapper.CONVERSATION_SELECT+ORDINARY_FILTER+"ORDER BY c.last_activity_at DESC,c.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ConversationPO> ordinaryConversations(@Param("projectId") Long projectId,@Param("userId") Long userId,
            @Param("keyword") String keyword,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT COUNT(*)"+ConversationMapper.CONVERSATION_FROM+ORDINARY_FILTER)
    long countOrdinaryConversations(@Param("projectId") Long projectId,@Param("userId") Long userId,@Param("keyword") String keyword);
    @Select("SELECT COUNT(*) FROM orchestration_execution WHERE project_id=#{id} AND status IN ("+ACTIVE+")")
    int pendingProject(Long id);
    @Select("SELECT COUNT(*) FROM orchestration_execution WHERE project_id=#{id} AND status IN ('RUNNING','CANCELING','NEEDS_ATTENTION')")
    int reservedProject(Long id);
}
