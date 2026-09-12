package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.SkillImportDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.SkillImportVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.SecureDigests;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
@PreAuthorize("hasAuthority('skill:manage')")
public class SkillImportService {
    private final SkillMapper skills;
    private final SkillImportMapper records;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Path root;
    @Value("${harness.skill-import.max-staged-bytes:1073741824}") private long maxStagedBytes = 1073741824L;
    @Value("${harness.skill-import.max-batch-bytes:524288000}") private long maxBatchBytes = 524288000L;
    @Value("${harness.skill-import.upload-ttl-hours:24}") private long uploadTtlHours = 24;

    public SkillImportService(SkillMapper skills, SkillImportMapper records, TransactionTemplate tx,
                              ObjectMapper json, AgentProperties properties) {
        this.skills = skills; this.records = records; this.tx = tx; this.json = json;
        this.root = Path.of(properties.getSkillStorageDir()).toAbsolutePath().normalize();
    }

    public SkillImportVO.Upload upload(MultipartFile file, Long user) throws IOException {
        SkillArchive.validateUpload(file);
        Files.createDirectories(root);
        String id = UUID.randomUUID().toString();
        Path path = root.resolve(id + ".zip");
        boolean saved = false;
        try {
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > SkillArchive.MAX_BYTES) throw conflict("Skill ZIP 不能超过 20MB");
                    output.write(buffer, 0, count);
                }
            }
            SkillArchive.Metadata metadata = SkillArchive.inspect(path);
            var staged = new SkillImportStatePO.Upload(path.toString(), Files.size(path), SkillArchive.digest(path),
                    file.getOriginalFilename(), metadata.name(), metadata.description());
            LocalDateTime expires = LocalDateTime.now().plusHours(uploadTtlHours);
            SkillImportVO.Upload result = tx.execute(status -> {
                skills.lockCatalog();
                List<SkillImportRecordPO> existing = records.uploads(user);
                long bytes = existing.stream().map(r -> read(r.payload(), SkillImportStatePO.Upload.class)).mapToLong(SkillImportStatePO.Upload::size).sum();
                if (existing.size() >= 100 || bytes + staged.size() > maxStagedBytes) throw conflict("暂存额度已满，请移除文件或等待过期清理");
                records.insert(new SkillImportRecordPO(id, "UPLOAD", user, write(staged), expires));
                SkillPO match = metadata.name().isBlank() ? null : skills.selectSkillByName(metadata.name());
                return new SkillImportVO.Upload(id, staged.filename(), staged.size(), staged.sha256(), metadata.name(),
                        metadata.description(), match == null ? null : match.getId(), expires);
            });
            saved = true;
            return result;
        } finally { if (!saved) Files.deleteIfExists(path); }
    }

    public SkillImportVO.Preview preview(SkillImportDTO request, Long user) {
        validateRequest(request);
        return tx.execute(status -> {
            skills.lockCatalog();
            List<SkillImportVO.Item> items = new ArrayList<>();
            Set<String> targets = new HashSet<>();
            long bytes = 0;
            for (var item : request.items()) {
                try {
                    var upload = staged(item.uploadId(), user);
                    bytes += upload.size();
                    SkillImportVO.Item checked = inspect(request.mode(), item, upload);
                    String key = request.mode() == SkillImportDTO.Mode.CREATE ? SkillArchive.name(item.skillName()).toLowerCase(Locale.ROOT) : String.valueOf(item.skillId());
                    if (!targets.add(key)) throw conflict("同批次不能重复提交同一个 Skill");
                    items.add(checked);
                } catch (BusinessException error) {
                    items.add(new SkillImportVO.Item(item.itemId(), item.skillId(), item.skillName(), item.version(), null,
                            "INVALID", error.getMessage(), List.of(), ""));
                }
            }
            if (bytes > maxBatchBytes) throw conflict("本批文件总大小超过限制");
            if (records.countPreviews(user, LocalDateTime.now()) >= 100) throw conflict("预览数量过多，请等待过期后重试");
            String id = UUID.randomUUID().toString();
            LocalDateTime expires = LocalDateTime.now().plusHours(1);
            long count = items.stream().flatMap(i -> i.experts().stream()).map(SkillImportVO.Impact::expertId).distinct().count();
            var view = new SkillImportVO.Preview(id, List.copyOf(items), count, expires);
            records.insert(new SkillImportRecordPO(id, "PREVIEW", user, write(new SkillImportStatePO.Preview(request, view)), expires));
            return view;
        });
    }

    public SkillImportVO.Submission commit(SkillImportDTO.Commit input, Long user) {
        String key = submissionKey(input.submissionId(), user);
        SkillImportStatePO.Submission submission = tx.execute(status -> {
            skills.lockCatalog();
            SkillImportRecordPO existing = records.get(key);
            if (existing != null) {
                var saved = read(require(key, "SUBMISSION", user).payload(), SkillImportStatePO.Submission.class);
                if (!saved.previewId().equals(input.previewId())) throw conflict("提交标识已用于其他预览");
                return saved;
            }
            var preview = read(require(input.previewId(), "PREVIEW", user).payload(), SkillImportStatePO.Preview.class);
            for (int index = 0; index < preview.request().items().size(); index++) check(preview, index, user);
            var created = new SkillImportStatePO.Submission(input.previewId(), preview, List.of());
            records.insert(new SkillImportRecordPO(key, "SUBMISSION", user, write(created), LocalDateTime.now().plusDays(7)));
            return created;
        });
        for (int index = 0; index < submission.preview().request().items().size(); index++) {
            final int position = index;
            try {
                // Hash before the catalog transaction so large archives do not lengthen the lock.
                var latest = read(require(key, "SUBMISSION", user).payload(), SkillImportStatePO.Submission.class);
                var candidate = latest.preview().request().items().get(position);
                if (latest.results().stream().noneMatch(r -> r.itemId().equals(candidate.itemId()))) {
                    SkillImportRecordPO stagedRecord = records.get(candidate.uploadId());
                    // A concurrent replay may already have consumed it; the locked transaction below resolves that case.
                    if (stagedRecord != null && stagedRecord.kind().equals("UPLOAD") && stagedRecord.ownerId().equals(user)) {
                        var archive = read(stagedRecord.payload(), SkillImportStatePO.Upload.class);
                        try {
                            if (!archive.sha256().equals(SkillArchive.digest(Path.of(archive.path())))) throw conflict("暂存文件校验失败，请重新上传");
                        } catch (IOException error) { throw conflict("暂存文件无法读取，请重新上传"); }
                    }
                }
                tx.executeWithoutResult(status -> {
                    skills.lockCatalog();
                    var current = read(require(key, "SUBMISSION", user).payload(), SkillImportStatePO.Submission.class);
                    var item = current.preview().request().items().get(position);
                    if (current.results().stream().anyMatch(r -> r.itemId().equals(item.itemId()))) return;
                    check(current.preview(), position, user);
                    var upload = staged(item.uploadId(), user);
                    SkillImportVO.Result result = publish(current.preview().request().mode(), item, upload, user);
                    append(key, current, result);
                    // Archive becomes the immutable version file. Only its staging reference is consumed.
                    if (!upload.path().equals(skills.selectVersion(result.versionId()).getStoragePath()))
                        records.expire(item.uploadId(), LocalDateTime.now().minusSeconds(1));
                    else records.delete(item.uploadId());
                });
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(getClass()).warn("Skill import item failed: {}", submission.preview().request().items().get(index).itemId(), error);
                String message = error instanceof BusinessException ? error.getMessage() : "保存失败，请重试或联系管理员";
                tx.executeWithoutResult(status -> {
                    skills.lockCatalog();
                    var current = read(require(key, "SUBMISSION", user).payload(), SkillImportStatePO.Submission.class);
                    var item = current.preview().request().items().get(position);
                    if (current.results().stream().noneMatch(r -> r.itemId().equals(item.itemId())))
                        append(key, current, new SkillImportVO.Result(item.itemId(), "FAILED", message, item.skillId(), null));
                });
            }
        }
        return submission(input.submissionId(), user);
    }

    public SkillImportVO.Submission submission(String id, Long user) {
        var state = read(require(submissionKey(id, user), "SUBMISSION", user).payload(), SkillImportStatePO.Submission.class);
        List<SkillImportVO.Result> results = state.results();
        return new SkillImportVO.Submission(id, results.size() == state.preview().request().items().size(), results,
                results.stream().filter(r -> r.status().equals("SUCCESS")).count(),
                results.stream().filter(r -> r.status().equals("FAILED")).count(),
                results.stream().filter(r -> r.status().equals("SKIPPED")).count());
    }

    public void discard(String id, Long user) {
        tx.executeWithoutResult(status -> {
            skills.lockCatalog();
            SkillImportRecordPO record = records.get(id);
            if (record == null) return;
            if (!record.ownerId().equals(user) || !record.kind().equals("UPLOAD")) throw conflict("暂存文件不存在");
            records.expire(id, LocalDateTime.now().minusSeconds(1)); // Keep quota charged until cleanup actually reclaims the file.
        });
    }

    private void check(SkillImportStatePO.Preview preview, int index, Long user) {
        var expected = preview.view().items().get(index);
        if (!Set.of("READY", "SKIP").contains(expected.status())) throw conflict("请移除无效条目并重新预览");
        var item = preview.request().items().get(index);
        var actual = inspect(preview.request().mode(), item, staged(item.uploadId(), user));
        if (!actual.fingerprint().equals(expected.fingerprint())) throw conflict("版本或专家依赖已变化，请重新预览并确认");
    }

    private SkillImportVO.Item inspect(SkillImportDTO.Mode mode, SkillImportDTO.Item item, SkillImportStatePO.Upload upload) {
        String version = SkillArchive.version(item.version());
        SkillPO skill = mode == SkillImportDTO.Mode.CREATE ? skills.selectSkillByName(SkillArchive.name(item.skillName()))
                : item.skillId() == null ? null : skills.selectSkill(item.skillId());
        SkillArchive.description(item.description());
        SkillArchive.tag(item.tag());
        if (mode == SkillImportDTO.Mode.CREATE && skill != null) throw conflict("Skill 名称已存在，请使用批量更新");
        if (mode == SkillImportDTO.Mode.UPDATE && skill == null) throw conflict("请选择已有 Skill");
        List<SkillVersionPO> versions = skill == null ? List.of() : skills.selectVersions(skill.getId());
        // Database collation is authoritative for version equality, just as it is for names.
        SkillVersionPO duplicate = skill == null ? null : records.versionByName(skill.getId(), version);
        if (duplicate != null && !duplicate.getSha256().equals(upload.sha256())) throw conflict("该版本已存在且文件内容不同");
        List<SkillVersionPO> active = versions.stream().filter(v -> "ACTIVE".equals(v.getStatus())).toList();
        List<SkillImportVO.Impact> impacts = duplicate != null ? List.of() : active.stream().flatMap(v -> records.impacts(v.getId()).stream()).toList();
        boolean tagChanged = skill != null && item.tag() != null && !SkillArchive.tag(item.tag()).equals(skill.getTag());
        String snapshot = write(List.of(upload.sha256(), item, skill == null ? "NEW" : List.of(skill.getId(), skill.getSkillName(), skill.getStatus(), skill.getTag()),
                versions.stream().map(v -> List.of(v.getId(),v.getVersion(),v.getStatus())).toList(), impacts));
        return new SkillImportVO.Item(item.itemId(), skill == null ? null : skill.getId(), skill == null ? item.skillName().trim() : skill.getSkillName(), version,
                active.isEmpty() ? null : active.getFirst().getVersion(), duplicate == null || tagChanged ? "READY" : "SKIP",
                duplicate == null ? "校验通过" : tagChanged ? "同版本同文件，仅更新标签，版本状态保持不变" : "同版本同文件，将跳过且保持激活状态", impacts, SecureDigests.sha256(snapshot));
    }

    private SkillImportVO.Result publish(SkillImportDTO.Mode mode, SkillImportDTO.Item item, SkillImportStatePO.Upload upload, Long user) {
        Long skillId = item.skillId();
        if (mode == SkillImportDTO.Mode.CREATE) {
            SkillPO skill = new SkillPO(); skill.setSkillName(SkillArchive.name(item.skillName()));
            skill.setDescription(SkillArchive.description(item.description())); skill.setCreatedBy(user);
            skill.setTag(SkillArchive.tag(item.tag()));
            skills.insertSkill(skill); skillId = skill.getId();
        } else {
            SkillPO skill = skills.lockSkill(skillId);
            boolean tagChanged = item.tag() != null && !SkillArchive.tag(item.tag()).equals(skill.getTag());
            if (tagChanged) skills.updateTag(skillId, SkillArchive.tag(item.tag()));
            SkillVersionPO duplicate = records.versionByName(skillId, SkillArchive.version(item.version()));
            if (duplicate != null) return new SkillImportVO.Result(item.itemId(), tagChanged ? "SUCCESS" : "SKIPPED",
                    tagChanged ? "标签已更新，版本状态保持不变" : "相同版本和文件已存在", skillId, duplicate.getId());
        }
        SkillVersionPO version = new SkillVersionPO(); version.setSkillId(skillId); version.setVersion(SkillArchive.version(item.version()));
        version.setStoragePath(upload.path()); version.setSha256(upload.sha256()); version.setFileSize(upload.size()); version.setCreatedBy(user);
        SkillVersionWriter.publish(skills, version);
        return new SkillImportVO.Result(item.itemId(), "SUCCESS", "上传成功，新版本已启用", skillId, version.getId());
    }

    private SkillImportStatePO.Upload staged(String id, Long user) {
        var upload = read(require(id, "UPLOAD", user).payload(), SkillImportStatePO.Upload.class);
        Path path = Path.of(upload.path()).toAbsolutePath().normalize();
        try {
            boolean managed = root.equals(path.getParent());
            if (!managed || !Files.isRegularFile(path) || Files.size(path) != upload.size()) throw conflict("暂存文件已丢失，请重新上传");
        } catch (IOException error) { throw conflict("无法读取暂存文件，请重新上传"); }
        return upload;
    }
    private void append(String key, SkillImportStatePO.Submission current, SkillImportVO.Result result) {
        List<SkillImportVO.Result> results = new ArrayList<>(current.results()); results.add(result);
        records.update(key, write(new SkillImportStatePO.Submission(current.previewId(), current.preview(), results)));
    }
    private SkillImportRecordPO require(String id, String kind, Long user) {
        SkillImportRecordPO record = records.get(id);
        if (record == null || !record.kind().equals(kind) || !record.ownerId().equals(user)) throw new BusinessException(ErrorCode.NOT_FOUND,"导入记录不存在");
        if (record.expiresAt().isBefore(LocalDateTime.now())) throw conflict("导入记录已过期，请重新上传或预览");
        return record;
    }
    private void validateRequest(SkillImportDTO request) {
        if (request == null || request.mode() == null || request.items() == null || request.items().isEmpty() || request.items().size() > 50)
            throw conflict("每批请选择 1～50 个文件");
        if (request.items().stream().map(SkillImportDTO.Item::itemId).distinct().count() != request.items().size()
                || request.items().stream().map(SkillImportDTO.Item::uploadId).distinct().count() != request.items().size()) throw conflict("条目标识或暂存文件重复");
    }
    private String submissionKey(String id, Long user) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) throw conflict("提交标识不正确");
        return "submit:" + user + ":" + id;
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, Class<T> type) { try { return json.readValue(value,type); } catch (Exception e) { throw new IllegalStateException("Invalid import record",e); } }
    private BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT,message); }
}
