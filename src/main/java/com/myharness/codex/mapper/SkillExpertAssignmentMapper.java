package com.myharness.codex.mapper;
import com.myharness.codex.entity.po.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface SkillExpertAssignmentMapper {
    String CANDIDATES=" FROM expert WHERE status<>'DISABLED' AND (#{keyword}='' OR name LIKE CONCAT('%',#{keyword},'%')) " +
            "AND NOT JSON_CONTAINS(skill_version_ids,CAST(#{versionIds} AS JSON),'$') ";
    @Select("SELECT *"+CANDIDATES+"ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<ExpertPO> candidates(@Param("versionIds") String versionIds,@Param("keyword") String keyword,@Param("size") int size,@Param("offset") int offset);
    @Select("SELECT COUNT(*)"+CANDIDATES)
    long count(@Param("versionIds") String versionIds,@Param("keyword") String keyword);
    @Insert("INSERT INTO skill_expert_assignment_batch(id,owner_id,skill_id,version_id,payload,started,expires_at) VALUES(#{id},#{ownerId},#{skillId},#{versionId},#{payload},0,#{expiresAt})")
    void insert(SkillExpertAssignmentBatchPO value);
    @Select("SELECT * FROM skill_expert_assignment_batch WHERE id=#{id}") SkillExpertAssignmentBatchPO get(String id);
    @Update("UPDATE skill_expert_assignment_batch SET started=1,expires_at=DATE_ADD(NOW(),INTERVAL 7 DAY) WHERE id=#{id}") void start(String id);
    @Select("SELECT * FROM skill_expert_assignment_batch WHERE started=1 ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<SkillExpertAssignmentBatchPO> history(@Param("size") int size,@Param("offset") int offset);
    @Select("SELECT display_name FROM sys_user WHERE id=#{id}") String ownerName(Long id);
    @Select("SELECT * FROM skill_expert_assignment_item WHERE batch_id=#{id} ORDER BY expert_id") List<SkillExpertAssignmentItemPO> results(String id);
    @Select("SELECT COUNT(*) FROM skill_expert_assignment_item WHERE batch_id=#{batchId} AND expert_id=#{expertId}")
    int processed(@Param("batchId") String batchId,@Param("expertId") Long expertId);
    @Insert("INSERT INTO skill_expert_assignment_item(batch_id,expert_id,payload) VALUES(#{batchId},#{expertId},#{payload})") void result(SkillExpertAssignmentItemPO value);
    @Update("UPDATE expert SET skill_version_ids=#{ids},draft_changed=1,revision=revision+1 WHERE id=#{id}")
    void assign(@Param("id") Long id,@Param("ids") String ids);
}
