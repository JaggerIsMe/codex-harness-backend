package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ProjectPO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** Mutations share the Project -> Conversation lock order used when creating a Turn. */
public interface ProjectManagementMapper {
    @Select("SELECT * FROM codex_project WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    ProjectPO lockProject(@Param("id") Long id,@Param("userId") Long userId);

    @Select("SELECT * FROM conversation WHERE id=#{id} AND project_id=#{projectId} AND user_id=#{userId} FOR UPDATE")
    ConversationPO lockConversation(@Param("projectId") Long projectId,@Param("id") Long id,@Param("userId") Long userId);

    @Select("SELECT id FROM conversation WHERE project_id=#{projectId} ORDER BY id FOR UPDATE")
    List<Long> lockProjectConversations(Long projectId);

    @Select("SELECT id FROM conversation_turn WHERE conversation_id=#{id} " +
            "AND status IN ('CREATED','RUNNING','WAITING_APPROVAL') LIMIT 1 FOR UPDATE")
    Long lockActiveTurn(Long id);

    @Update("UPDATE codex_project SET project_name=#{name} WHERE id=#{id} AND user_id=#{userId}")
    int renameProject(@Param("id") Long id,@Param("userId") Long userId,@Param("name") String name);

    @Update("UPDATE conversation SET title=#{title} WHERE id=#{id} AND project_id=#{projectId} AND user_id=#{userId}")
    int renameConversation(@Param("projectId") Long projectId,@Param("id") Long id,
            @Param("userId") Long userId,@Param("title") String title);

    // Retain transfer receipts until the existing cleanup job has reclaimed temporary bytes.
    @Update("UPDATE workspace_file_operation SET status=IF(status IN ('QUEUED','RUNNING'),'FAILED',status)," +
            "error=IF(status='FAILED','会话已删除',error),attachment_id=NULL " +
            "WHERE attachment_id IN (SELECT id FROM conversation_attachment WHERE conversation_id=#{id})")
    int detachConversationTransfers(Long id);

    @Delete("DELETE FROM conversation_message_attachment WHERE attachment_id IN " +
            "(SELECT id FROM conversation_attachment WHERE conversation_id=#{id})")
    int deleteMessageAttachments(Long id);

    @Delete("DELETE FROM conversation_attachment WHERE conversation_id=#{id}")
    int deleteAttachments(Long id);

    // Message, Turn and Approval Request rows are removed by their existing cascading foreign keys.
    @Delete("DELETE FROM conversation WHERE id=#{id}")
    int deleteConversation(Long id);

    @Delete("DELETE FROM project_expert_binding WHERE project_id=#{id}")
    int deleteExpertBindings(Long id);

    @Update("UPDATE workspace_file_operation SET status=IF(status IN ('QUEUED','RUNNING'),'FAILED',status)," +
            "error=IF(status='FAILED','项目已删除',error),attachment_id=NULL WHERE project_id=#{id}")
    int cancelProjectTransfers(Long id);

    // DISABLED survives Agent inventory upserts and rejects late workspace preparation results.
    @Update("UPDATE agent_workspace SET status='DISABLED' WHERE id=#{id} AND device_id=#{deviceId}")
    int disableWorkspace(@Param("id") Long id,@Param("deviceId") Long deviceId);

    @Delete("DELETE FROM codex_project WHERE id=#{id} AND user_id=#{userId}")
    int deleteProject(@Param("id") Long id,@Param("userId") Long userId);
}
