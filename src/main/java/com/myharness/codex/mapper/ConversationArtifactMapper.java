package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.ConversationArtifactPO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface ConversationArtifactMapper {
    @Insert("INSERT INTO conversation_artifact(user_id,project_id,conversation_id,turn_id,device_id,artifact_key,file_name,storage_key,media_type,size_bytes,sha256,status) VALUES(#{userId},#{projectId},#{conversationId},#{turnId},#{deviceId},#{artifactKey},#{fileName},#{storageKey},#{mediaType},#{sizeBytes},#{sha256},'UPLOADING')")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(ConversationArtifactPO p);
    @Select("SELECT * FROM conversation_artifact WHERE turn_id=#{tid} AND artifact_key=#{key}")
    ConversationArtifactPO findKey(@Param("tid") Long tid,@Param("key") String key);
    @Select("SELECT * FROM conversation_artifact WHERE id=#{id}")
    ConversationArtifactPO find(Long id);
    @Select("SELECT * FROM conversation_artifact WHERE id=#{id} FOR UPDATE")
    ConversationArtifactPO lock(Long id);
    @Select("SELECT * FROM conversation_artifact WHERE conversation_id=#{cid} ORDER BY id")
    List<ConversationArtifactPO> forConversation(Long cid);
    @Select("SELECT * FROM conversation_artifact WHERE turn_id=#{tid} ORDER BY id")
    List<ConversationArtifactPO> forTurn(Long tid);
    @Update("UPDATE conversation_artifact SET status='READY',error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='UPLOADING'")
    int ready(Long id);
    @Update("UPDATE conversation_artifact SET status='FAILED',error_message=#{reason},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='UPLOADING'")
    int fail(@Param("id") Long id,@Param("reason") String reason);
    @Update("UPDATE conversation_artifact SET status='UPLOADING',error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='FAILED'")
    int retry(Long id);
    @Select("SELECT * FROM conversation_artifact WHERE status='UPLOADING' AND updated_at<#{deadline} ORDER BY id LIMIT 200")
    List<ConversationArtifactPO> stalled(LocalDateTime deadline);
    @Update("UPDATE conversation_artifact SET status='FAILED',error_message='上传超时，请重试；源设备需在线',updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='UPLOADING' AND updated_at<#{deadline}")
    int failStalled(@Param("id") Long id,@Param("deadline") LocalDateTime deadline);
}
