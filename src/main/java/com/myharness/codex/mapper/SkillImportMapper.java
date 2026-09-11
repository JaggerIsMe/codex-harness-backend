package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.SkillImportRecordPO;
import com.myharness.codex.entity.vo.SkillImportVO.Impact;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface SkillImportMapper {
    @Select("SELECT COUNT(*) FROM skill_import_record WHERE kind='PREVIEW' AND owner_id=#{ownerId} AND expires_at>#{now}")
    int countPreviews(@Param("ownerId") Long ownerId, @Param("now") LocalDateTime now);
    @Select("SELECT * FROM skill_version WHERE skill_id=#{skillId} AND version=#{version}")
    com.myharness.codex.entity.po.SkillVersionPO versionByName(@Param("skillId") Long skillId, @Param("version") String version);
    @Insert("INSERT INTO skill_import_record(id,kind,owner_id,payload,expires_at) VALUES(#{id},#{kind},#{ownerId},#{payload},#{expiresAt})")
    int insert(SkillImportRecordPO record);
    @Select("SELECT id,kind,owner_id,payload,expires_at FROM skill_import_record WHERE id=#{id}")
    SkillImportRecordPO get(String id);
    @Update("UPDATE skill_import_record SET payload=#{payload} WHERE id=#{id}")
    int update(@Param("id") String id, @Param("payload") String payload);
    @Delete("DELETE FROM skill_import_record WHERE id=#{id}") int delete(String id);
    @Update("UPDATE skill_import_record SET expires_at=#{expiredAt} WHERE id=#{id}")
    int expire(@Param("id") String id, @Param("expiredAt") LocalDateTime expiredAt);
    @Select("SELECT id,kind,owner_id,payload,expires_at FROM skill_import_record WHERE owner_id=#{ownerId} AND kind='UPLOAD'")
    List<SkillImportRecordPO> uploads(Long ownerId);
    @Select("SELECT id,kind,owner_id,payload,expires_at FROM skill_import_record WHERE expires_at < #{now} ORDER BY expires_at LIMIT 200")
    List<SkillImportRecordPO> expired(LocalDateTime now);
    @Select("SELECT COUNT(*) FROM skill_version WHERE storage_path=#{path}") int references(String path);
    @Select("SELECT COUNT(*) FROM skill_import_record WHERE kind='UPLOAD' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.path'))=#{path}")
    int stagedAtPath(String path);
    @Select("SELECT e.id expert_id,e.name expert_name,e.status expert_status,'DRAFT' source," +
            "NULL expert_version_id,NULL expert_version_no,#{versionId} skill_version_id FROM expert e " +
            "WHERE JSON_CONTAINS(e.skill_version_ids,CAST(#{versionId} AS JSON),'$') " +
            "UNION ALL SELECT e.id,e.name,e.status,'VERSION',v.id,v.version_no,#{versionId} " +
            "FROM expert_version v JOIN expert e ON e.id=v.expert_id " +
            "WHERE JSON_CONTAINS(v.skill_version_ids,CAST(#{versionId} AS JSON),'$') " +
            "ORDER BY expert_id,source,expert_version_id")
    List<Impact> impacts(Long versionId);
}
