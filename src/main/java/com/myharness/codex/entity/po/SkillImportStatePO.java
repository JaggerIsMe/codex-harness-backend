package com.myharness.codex.entity.po;

import com.myharness.codex.entity.dto.SkillImportDTO;
import com.myharness.codex.entity.vo.SkillImportVO;
import java.util.List;

public final class SkillImportStatePO {
    private SkillImportStatePO() {}
    public record Upload(String path, long size, String sha256, String filename, String name, String description) {}
    public record Preview(SkillImportDTO request, SkillImportVO.Preview view) {}
    public record Submission(String previewId, Preview preview, List<SkillImportVO.Result> results) {}
}
