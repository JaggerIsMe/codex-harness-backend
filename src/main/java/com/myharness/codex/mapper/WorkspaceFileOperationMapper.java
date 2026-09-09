package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.WorkspaceFileOperationPO;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;

public interface WorkspaceFileOperationMapper {
    @Update("UPDATE workspace_file_operation SET attachment_id=NULL WHERE id=#{id}") int detach(Long id);
    @Select("SELECT * FROM workspace_file_operation WHERE device_id=#{id} AND status='RUNNING'") List<WorkspaceFileOperationPO> runningForDevice(Long id);
    @Select("SELECT COUNT(*) FROM workspace_file_operation WHERE project_id=#{pid} AND status IN ('QUEUED','RUNNING')") int activeCount(Long pid);
    @Update("UPDATE workspace_file_operation SET status='EXPIRED' WHERE id=#{id} AND status IN ('SUCCEEDED','FAILED') AND attachment_id IS NULL") int expire(Long id);
    @Insert("INSERT INTO workspace_file_operation(request_key,user_id,project_id,device_id,workspace_name,kind,path,page_cursor,status,storage_key,sha256,size_bytes,attachment_id) VALUES(#{requestKey},#{userId},#{projectId},#{deviceId},#{workspaceName},#{kind},#{path},#{cursor},'QUEUED',#{storageKey},#{sha256},#{sizeBytes},#{attachmentId})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(WorkspaceFileOperationPO p);
    @Select("SELECT * FROM workspace_file_operation WHERE id=#{id}") WorkspaceFileOperationPO get(Long id);
    @Select("SELECT * FROM workspace_file_operation WHERE project_id=#{pid} AND request_key=#{key}") WorkspaceFileOperationPO request(@Param("pid") Long pid,@Param("key") String key);
    @Select("SELECT * FROM workspace_file_operation WHERE project_id=#{pid} AND kind='SYNC_WORKSPACE_TREE' AND path=#{path} AND page_cursor=#{cursor} ORDER BY id DESC LIMIT 1") WorkspaceFileOperationPO latest(@Param("pid") Long pid,@Param("path") String path,@Param("cursor") String cursor);
    @Select("SELECT * FROM workspace_file_operation WHERE status='QUEUED' ORDER BY id LIMIT 100") List<WorkspaceFileOperationPO> queued();
    @Select("SELECT COUNT(*) FROM workspace_file_operation WHERE project_id=#{pid} AND status='RUNNING'") int running(Long pid);
    @Update("UPDATE workspace_file_operation SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='QUEUED'") int start(Long id);
    @Update("UPDATE workspace_file_operation SET status=#{status},error=#{error},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status IN ('QUEUED','RUNNING')") int finish(@Param("id") Long id,@Param("status") String status,@Param("error") String error);
    @Update("UPDATE workspace_file_operation SET size_bytes=#{size},sha256=#{sha},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='RUNNING'") int content(@Param("id") Long id,@Param("size") long size,@Param("sha") String sha);
    @Select("SELECT * FROM workspace_file_operation WHERE status IN ('RUNNING','QUEUED') AND updated_at<#{deadline} LIMIT 100") List<WorkspaceFileOperationPO> timedOut(LocalDateTime deadline);
    @Select("SELECT * FROM workspace_file_operation WHERE (attachment_id IS NULL OR storage_key IS NOT NULL) AND updated_at<#{deadline} AND status IN ('SUCCEEDED','FAILED') LIMIT 200") List<WorkspaceFileOperationPO> expired(LocalDateTime deadline);
    @Update("UPDATE workspace_file_operation SET storage_key=NULL WHERE id=#{id} AND status IN ('SUCCEEDED','FAILED')") int releaseContent(Long id);
}
