package com.myharness.codex.mapper;
import com.myharness.codex.entity.po.ConversationAttachmentPO;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;
public interface ConversationAttachmentMapper {
    @Select("SELECT id FROM codex_project WHERE id=#{id} FOR UPDATE")
    Long lockProject(Long id);
    String LOCATION_MATCH = "project_id=#{pid} AND status IN ('PENDING','ATTACHED') AND " +
            "(BINARY workspace_path=BINARY #{path} OR BINARY LEFT(workspace_path,CHAR_LENGTH(#{path})+1)=BINARY CONCAT(#{path},'/'))";
    @Select("SELECT * FROM conversation_attachment WHERE " + LOCATION_MATCH + " ORDER BY conversation_id,id")
    List<ConversationAttachmentPO> atLocation(@Param("pid") Long pid,@Param("path") String path);
    @Select("SELECT COUNT(*) FROM conversation_attachment WHERE " + LOCATION_MATCH + " AND workspace_location_state='AVAILABLE'")
    long countAvailableAtLocation(@Param("pid") Long pid,@Param("path") String path);
    @Update("UPDATE conversation_attachment SET last_file_operation_id=#{operationId} WHERE id=#{id} " +
            "AND location_revision=#{revision} AND workspace_location_state='AVAILABLE' AND status IN ('PENDING','ATTACHED')")
    int claimLocation(@Param("id") Long id,@Param("revision") long revision,@Param("operationId") Long operationId);
    @Update("UPDATE conversation_attachment SET workspace_path=#{path},workspace_location_state=#{state}," +
            "location_revision=location_revision+1 WHERE id=#{id} AND location_revision=#{revision} " +
            "AND last_file_operation_id=#{operationId} AND status IN ('PENDING','ATTACHED')")
    int updateLocation(@Param("id") Long id,@Param("revision") long revision,@Param("operationId") Long operationId,
            @Param("path") String path,@Param("state") String state);
    @Update("UPDATE conversation_attachment SET workspace_path=#{path},workspace_operation_id=#{operationId} WHERE id=#{id}")
    int workspace(@Param("id") Long id,@Param("path") String path,@Param("operationId") Long operationId);
    @Insert("INSERT INTO conversation_attachment(user_id,project_id,conversation_id,file_name,storage_key,media_type,size_bytes,sha256,status) VALUES(#{userId},#{projectId},#{conversationId},#{fileName},#{storageKey},#{mediaType},#{sizeBytes},#{sha256},'PENDING')")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(ConversationAttachmentPO value);
    @Select("SELECT * FROM conversation_attachment WHERE id=#{id} AND conversation_id=#{cid} AND status IN ('PENDING','ATTACHED')")
    ConversationAttachmentPO find(@Param("cid") Long cid,@Param("id") Long id);
    @Select("SELECT * FROM conversation_attachment WHERE conversation_id=#{cid} AND status='PENDING' ORDER BY id")
    List<ConversationAttachmentPO> pending(Long cid);
    @Select("SELECT * FROM conversation_attachment WHERE id=#{id} FOR UPDATE")
    ConversationAttachmentPO lock(Long id);
    @Update("UPDATE conversation_attachment SET status='ATTACHED' WHERE id=#{id} AND status IN ('PENDING','ATTACHED')")
    int attach(Long id);
    @Insert("INSERT INTO conversation_message_attachment(message_id,attachment_id,position) SELECT id,#{aid},#{position} FROM conversation_message WHERE turn_id=#{tid} AND role='USER'")
    int link(@Param("tid") Long tid,@Param("aid") Long aid,@Param("position") int position);
    @Select("SELECT a.* FROM conversation_attachment a JOIN conversation_message_attachment ma ON ma.attachment_id=a.id JOIN conversation_message m ON m.id=ma.message_id WHERE m.turn_id=#{tid} AND a.status='ATTACHED' ORDER BY ma.position")
    List<ConversationAttachmentPO> forTurn(Long tid);
    @Select("<script>SELECT a.*,ma.message_id FROM conversation_attachment a JOIN conversation_message_attachment ma ON ma.attachment_id=a.id WHERE a.status='ATTACHED' AND ma.message_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY ma.message_id,ma.position</script>")
    List<com.myharness.codex.entity.po.ConversationMessageAttachmentPO> forMessages(@Param("ids") List<Long> ids);
    @Update("UPDATE conversation_attachment SET status='DELETED' WHERE id=#{id} AND status='PENDING'")
    int markDeleted(Long id);
    @Select("SELECT * FROM conversation_attachment WHERE (status='PENDING' AND created_at<#{deadline}) OR status='DELETED' ORDER BY id LIMIT 200")
    List<ConversationAttachmentPO> expired(LocalDateTime deadline);
    @Delete("DELETE FROM conversation_attachment WHERE id=#{id} AND status='DELETED'")
    int purge(Long id);
}
