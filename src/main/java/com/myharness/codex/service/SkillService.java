package com.myharness.codex.service;

import com.myharness.codex.entity.dto.SkillVersionStatusDTO;
import com.myharness.codex.entity.dto.UpdateSkillDTO;
import com.myharness.codex.entity.vo.SkillFileVO;
import com.myharness.codex.entity.vo.SkillVO;
import com.myharness.codex.entity.vo.SkillVersionVO;
import com.myharness.codex.entity.vo.PageVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface SkillService {
    List<SkillVO> list(String keyword, String status);
    PageVO<SkillVO> page(String keyword, String status, int page, int size);
    List<SkillVO> selected(List<Long> ids);
    SkillVO get(Long skillId);
    SkillVO create(String skillName, String description, String tag, String version, MultipartFile file, Long operatorId) throws IOException;
    SkillVersionVO uploadVersion(Long skillId, String version, MultipartFile file, Long operatorId) throws IOException;
    SkillVO update(Long skillId, UpdateSkillDTO dto);
    SkillVersionVO updateVersionStatus(Long skillId, Long versionId, SkillVersionStatusDTO dto);
    SkillFileVO download(Long skillId, Long versionId) throws IOException;
}
