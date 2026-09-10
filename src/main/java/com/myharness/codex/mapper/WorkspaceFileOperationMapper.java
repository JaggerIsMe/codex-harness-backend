package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.WorkspaceFileOperationPO;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;

public interface WorkspaceFileOperationMapper {
    String MUTATIONS="('RELOCATE_WORKSPACE_ENTRY','DELETE_WORKSPACE_ENTRY')";
    @Select("SELECT COUNT(*) FROM workspace_file_operation WHERE project_id=#{pid} AND kind IN "+MUTATIONS+" AND status IN ('QUEUED','RUNNING','UNKNOWN')") int mutationCount(Long pid);
    @Select("SELECT * FROM workspace_file_operation WHERE device_id=#{id} AND status='UNKNOWN' AND kind IN "+MUTATIONS) List<WorkspaceFileOperationPO> unknownForDevice(Long id);
    @Select("SELECT * FROM workspace_file_operation WHERE project_id=#{pid} AND kind<>'SYNC_WORKSPACE_TREE' AND (#{cursor} IS NULL OR id<#{cursor}) ORDER BY id DESC LIMIT #{limit}")
    List<WorkspaceFileOperationPO> recent(@Param("pid") Long pid,@Param("cursor") Long cursor,@Param("limit") int limit);
    @Select("SELECT * FROM workspace_file_operation WHERE project_id=#{pid} AND kind='PREPARE_WORKSPACE_DELETE' AND plan_id=#{planId} AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1")
    WorkspaceFileOperationPO deletePlan(@Param("pid") Long pid,@Param("planId") String planId);
    @Select("SELECT * FROM workspace_file_operation WHERE delete_plan_operation_id=#{id}") WorkspaceFileOperationPO planConsumer(Long id);
    @Update("UPDATE workspace_file_operation SET target_path=#{targetPath},request_digest=#{requestDigest},payload_json=#{payloadJson},delete_plan_operation_id=#{deletePlanOperationId},content_state=#{contentState},attachment_count=#{attachmentCount} WHERE id=#{id} AND status='QUEUED'")
    int actionPayload(WorkspaceFileOperationPO p);
    @Update("UPDATE workspace_file_operation SET result_json=#{resultJson},code=#{code},plan_id=#{planId},status=#{status},error=#{error},content_state=#{contentState},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status IN ('RUNNING','UNKNOWN','QUEUED')")
    int actionResult(WorkspaceFileOperationPO p);
    @Delete("DELETE FROM workspace_file_operation_item WHERE operation_id=#{id}") int clearItems(Long id);
    @Insert("INSERT INTO workspace_file_operation_item(operation_id,item_index,path,entry_type,status,code,error) VALUES(#{id},#{index},#{item.path},#{item.entryType},#{item.status},#{item.code},#{item.error})")
    int insertItem(@Param("id") Long id,@Param("index") int index,@Param("item") com.myharness.codex.entity.dto.WorkspaceFileItemsDTO.Item item);
    @Select("SELECT item_index,path,entry_type,status,code,error FROM workspace_file_operation_item WHERE operation_id=#{id} AND item_index>#{cursor} ORDER BY item_index LIMIT #{limit}")
    List<com.myharness.codex.entity.po.WorkspaceFileItemPO> items(@Param("id") Long id,@Param("cursor") long cursor,@Param("limit") int limit);
    @Update("UPDATE workspace_file_operation SET items_digest=#{sha} WHERE id=#{id} AND status IN ('RUNNING','UNKNOWN')") int itemsDigest(@Param("id") Long id,@Param("sha") String sha);
    @Update("UPDATE workspace_file_operation SET attachment_id=NULL WHERE id=#{id}") int detach(Long id);
    @Select("SELECT * FROM workspace_file_operation WHERE device_id=#{id} AND status='RUNNING'") List<WorkspaceFileOperationPO> runningForDevice(Long id);
    @Select("SELECT COUNT(*) FROM workspace_file_operation WHERE project_id=#{pid} AND status IN ('QUEUED','RUNNING')") int activeCount(Long pid);
    @Update("UPDATE workspace_file_operation SET status='EXPIRED' WHERE id=#{id} AND status IN ('SUCCEEDED','FAILED') AND attachment_id IS NULL AND request_digest IS NULL") int expire(Long id);
    @Insert("INSERT INTO workspace_file_operation(request_key,user_id,project_id,device_id,workspace_name,kind,path,page_cursor,status,storage_key,sha256,size_bytes,attachment_id) VALUES(#{requestKey},#{userId},#{projectId},#{deviceId},#{workspaceName},#{kind},#{path},#{cursor},'QUEUED',#{storageKey},#{sha256},#{sizeBytes},#{attachmentId})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(WorkspaceFileOperationPO p);
    @Select("SELECT * FROM workspace_file_operation WHERE id=#{id}") WorkspaceFileOperationPO get(Long id);
    @Select("SELECT * FROM workspace_file_operation WHERE project_id=#{pid} AND request_key=#{key}") WorkspaceFileOperationPO request(@Param("pid") Long pid,@Param("key") String key);
    @Select("SELECT * FROM workspace_file_operation WHERE project_id=#{pid} AND kind='SYNC_WORKSPACE_TREE' AND path=#{path} AND page_cursor=#{cursor} ORDER BY id DESC LIMIT 1") WorkspaceFileOperationPO latest(@Param("pid") Long pid,@Param("path") String path,@Param("cursor") String cursor);
    @Select("SELECT * FROM workspace_file_operation WHERE status='QUEUED' ORDER BY id LIMIT 100") List<WorkspaceFileOperationPO> queued();
    @Select("SELECT COUNT(*) FROM workspace_file_operation WHERE project_id=#{pid} AND status IN ('RUNNING','UNKNOWN')") int running(Long pid);
    @Update("UPDATE workspace_file_operation SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='QUEUED'") int start(Long id);
    @Update("UPDATE workspace_file_operation SET status=#{status},error=#{error},content_state=IF(#{status}='SUCCEEDED' AND storage_key IS NOT NULL,'AVAILABLE',content_state),updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status IN ('QUEUED','RUNNING')") int finish(@Param("id") Long id,@Param("status") String status,@Param("error") String error);
    @Update("UPDATE workspace_file_operation SET size_bytes=#{size},sha256=#{sha},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='RUNNING'") int content(@Param("id") Long id,@Param("size") long size,@Param("sha") String sha);
    @Select("SELECT * FROM workspace_file_operation WHERE status IN ('RUNNING','QUEUED') AND updated_at<#{deadline} LIMIT 100") List<WorkspaceFileOperationPO> timedOut(LocalDateTime deadline);
    @Select("SELECT * FROM workspace_file_operation WHERE storage_key IS NOT NULL AND updated_at<#{deadline} AND status IN ('SUCCEEDED','FAILED','PARTIAL_FAILED') LIMIT 200") List<WorkspaceFileOperationPO> expired(LocalDateTime deadline);
    @Update("UPDATE workspace_file_operation SET storage_key=NULL,content_state='EXPIRED' WHERE id=#{id} AND status IN ('SUCCEEDED','FAILED','PARTIAL_FAILED')") int releaseContent(Long id);
}
