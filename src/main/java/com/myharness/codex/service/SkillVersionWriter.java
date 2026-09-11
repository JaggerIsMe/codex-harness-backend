package com.myharness.codex.service;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SkillVersionPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SkillMapper;
import org.springframework.dao.DuplicateKeyException;

/** Both upload paths enter here with a validated archive and the catalog lock held in a transaction. */
public final class SkillVersionWriter {
    private SkillVersionWriter() {}
    public static void publish(SkillMapper mapper, SkillVersionPO version) {
        mapper.disableActiveVersions(version.getSkillId());
        try { mapper.insertVersion(version); }
        catch (DuplicateKeyException error) { throw new BusinessException(ErrorCode.CONFLICT,"该 Skill 版本已存在"); }
    }
}
