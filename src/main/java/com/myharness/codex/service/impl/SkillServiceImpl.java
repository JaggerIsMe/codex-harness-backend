package com.myharness.codex.service.impl;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.SkillVersionStatusDTO;
import com.myharness.codex.entity.dto.UpdateSkillDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SkillPO;
import com.myharness.codex.entity.po.SkillVersionPO;
import com.myharness.codex.entity.vo.SkillFileVO;
import com.myharness.codex.entity.vo.SkillVO;
import com.myharness.codex.entity.vo.SkillVersionVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SkillMapper;
import com.myharness.codex.service.SkillService;
import com.myharness.codex.service.SkillArchive;
import com.myharness.codex.service.SkillVersionWriter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('skill:manage')")
public class SkillServiceImpl implements SkillService {
    private static final long MAX_UPLOAD_BYTES = 20L * 1024L * 1024L;
    private final SkillMapper mapper;
    private final Path storageRoot;
    private final TransactionTemplate tx;

    public SkillServiceImpl(SkillMapper mapper, AgentProperties properties, TransactionTemplate tx) {
        this.tx = tx;
        this.mapper = mapper;
        this.storageRoot = Paths.get(properties.getSkillStorageDir()).toAbsolutePath().normalize();
    }

    @Override
    public List<SkillVO> list(String keyword, String status) {
        validateSkillStatus(status, true);
        return mapper.selectSkills(trim(keyword), trim(status)).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public SkillVO get(Long skillId) { return toVO(requireSkill(skillId)); }

    @Override
    public SkillVO create(String skillName, String description, String version, MultipartFile file, Long operatorId) throws IOException {
        String normalizedName = validateName(skillName);
        String normalizedDescription = validateDescription(description);
        SkillVersionPO prepared = prepareVersion(version, file, operatorId);
        return publishPrepared(prepared, () -> {
            SkillPO skill = new SkillPO();
            skill.setSkillName(normalizedName); skill.setDescription(normalizedDescription); skill.setCreatedBy(operatorId);
            try { mapper.insertSkill(skill); }
            catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT, "Skill 名称已存在"); }
            prepared.setSkillId(skill.getId());
            SkillVersionWriter.publish(mapper, prepared);
            return toVO(requireSkill(skill.getId()));
        });
    }

    @Override
    public SkillVersionVO uploadVersion(Long skillId, String version, MultipartFile file, Long operatorId) throws IOException {
        requireSkill(skillId);
        SkillVersionPO prepared = prepareVersion(version, file, operatorId);
        return publishPrepared(prepared, () -> {
            requireLockedSkill(skillId);
            prepared.setSkillId(skillId);
            SkillVersionWriter.publish(mapper, prepared);
            return new SkillVersionVO(mapper.selectVersion(prepared.getId()));
        });
    }

    private <T> T publishPrepared(SkillVersionPO prepared, java.util.function.Supplier<T> publish) {
        Path file = Path.of(prepared.getStoragePath());
        try {
            return tx.execute(status -> {
                mapper.lockCatalog();
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCompletion(int state) { if (state != STATUS_COMMITTED) removeFailedArchive(file); }
                    });
                }
                return publish.get();
            });
        } catch (RuntimeException | Error error) { removeFailedArchive(file); throw error; }
    }

    private void removeFailedArchive(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException error) { org.slf4j.LoggerFactory.getLogger(SkillServiceImpl.class).warn("Could not remove rolled back Skill archive", error); }
    }

    @Override
    @Transactional
    public SkillVO update(Long skillId, UpdateSkillDTO dto) {
        mapper.lockCatalog();
        SkillPO skill = requireSkill(skillId);
        skill.setSkillName(validateName(dto.getSkillName()));
        skill.setDescription(validateDescription(dto.getDescription()));
        validateSkillStatus(dto.getStatus(), false); skill.setStatus(dto.getStatus());
        try { mapper.updateSkill(skill); }
        catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT, "Skill 名称已存在"); }
        return toVO(requireSkill(skillId));
    }

    @Override
    @Transactional
    public SkillVersionVO updateVersionStatus(Long skillId, Long versionId, SkillVersionStatusDTO dto) {
        mapper.lockCatalog();
        requireLockedSkill(skillId);
        SkillVersionPO version = requireVersion(skillId, versionId);
        if ("ACTIVE".equals(dto.getStatus())) {
            List<SkillVersionPO> versions = mapper.selectVersions(skillId);
            if (versions.isEmpty() || !versionId.equals(versions.getFirst().getId()))
                throw new BusinessException(ErrorCode.CONFLICT, "只能激活该 Skill 的最新版本");
            mapper.disableActiveVersions(skillId);
        }
        mapper.updateVersionStatus(versionId, dto.getStatus());
        return new SkillVersionVO(requireVersion(skillId, versionId));
    }

    @Override
    public SkillFileVO download(Long skillId, Long versionId) throws IOException {
        SkillPO skill = requireSkill(skillId);
        SkillVersionPO version = requireVersion(skillId, versionId);
        Path file = Paths.get(version.getStoragePath()).toAbsolutePath().normalize();
        if (!file.startsWith(storageRoot) || !Files.isRegularFile(file) || Files.size(file) != version.getFileSize()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 文件不存在或已损坏");
        }
        return new SkillFileVO(new FileSystemResource(file.toFile()), version.getFileSize(),
                skill.getSkillName() + "-" + version.getVersion() + ".zip");
    }

    private SkillVersionPO prepareVersion(String rawVersion, MultipartFile upload, Long operatorId) throws IOException {
        String version = validateVersion(rawVersion);
        validateUpload(upload);
        Files.createDirectories(storageRoot);
        Path temporary = Files.createTempFile(storageRoot, ".upload-", ".zip");
        Path target = storageRoot.resolve(UUID.randomUUID().toString() + ".zip").normalize();
        boolean stored = false;
        try (InputStream input = upload.getInputStream()) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temporary) > MAX_UPLOAD_BYTES) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill ZIP 不能超过 20MB");
            validateArchive(temporary);
            SkillVersionPO value = new SkillVersionPO();
            value.setVersion(version); value.setStoragePath(target.toString());
            value.setSha256(SkillArchive.digest(temporary)); value.setFileSize(Files.size(temporary)); value.setCreatedBy(operatorId);
            move(temporary, target);
            stored = true;
            return value;
        } finally {
            Files.deleteIfExists(temporary);
            if (!stored) Files.deleteIfExists(target);
        }
    }

    private void validateUpload(MultipartFile file) { SkillArchive.validateUpload(file); }
    private void validateArchive(Path archive) throws IOException { SkillArchive.inspect(archive); }

    private SkillVO toVO(SkillPO skill) {
        List<SkillVersionVO> versions = mapper.selectVersions(skill.getId()).stream().map(SkillVersionVO::new).collect(Collectors.toList());
        return new SkillVO(skill, versions);
    }
    private SkillPO requireSkill(Long id) {
        SkillPO value = mapper.selectSkill(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 不存在");
        return value;
    }
    private SkillPO requireLockedSkill(Long id) {
        SkillPO value = mapper.lockSkill(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 不存在");
        return value;
    }
    private SkillVersionPO requireVersion(Long skillId, Long versionId) {
        SkillVersionPO value = mapper.selectVersion(versionId);
        if (value == null || !skillId.equals(value.getSkillId())) throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 版本不存在");
        return value;
    }
    private String validateName(String value) { return SkillArchive.name(value); }
    private String validateVersion(String value) { return SkillArchive.version(value); }
    private String validateDescription(String value) { return SkillArchive.description(value); }
    private void validateSkillStatus(String status, boolean blankAllowed) {
        if (blankAllowed && (status == null || status.trim().isEmpty())) return;
        if (!("ENABLED".equals(status) || "DISABLED".equals(status))) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill 状态不正确");
    }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); }
    }
}
