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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SkillServiceImpl implements SkillService {
    private static final long MAX_UPLOAD_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 500;
    private final SkillMapper mapper;
    private final Path storageRoot;

    public SkillServiceImpl(SkillMapper mapper, AgentProperties properties) {
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
    @Transactional(rollbackFor = Exception.class)
    public SkillVO create(String skillName, String description, String version, MultipartFile file, Long operatorId) throws IOException {
        String normalizedName = validateName(skillName);
        SkillPO skill = new SkillPO();
        skill.setSkillName(normalizedName); skill.setDescription(validateDescription(description)); skill.setCreatedBy(operatorId);
        try { mapper.insertSkill(skill); }
        catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT, "Skill 名称已存在"); }
        storeVersion(skill.getId(), version, file, operatorId);
        return toVO(requireSkill(skill.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVersionVO uploadVersion(Long skillId, String version, MultipartFile file, Long operatorId) throws IOException {
        requireSkill(skillId);
        return new SkillVersionVO(storeVersion(skillId, version, file, operatorId));
    }

    @Override
    @Transactional
    public SkillVO update(Long skillId, UpdateSkillDTO dto) {
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
        requireSkill(skillId);
        SkillVersionPO version = requireVersion(skillId, versionId);
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

    private SkillVersionPO storeVersion(Long skillId, String rawVersion, MultipartFile upload, Long operatorId) throws IOException {
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
            value.setSkillId(skillId); value.setVersion(version); value.setStoragePath(target.toString());
            value.setSha256(sha256(temporary)); value.setFileSize(Files.size(temporary)); value.setCreatedBy(operatorId);
            move(temporary, target);
            try { mapper.insertVersion(value); }
            catch (DuplicateKeyException exception) {
                Files.deleteIfExists(target); stored = false;
                throw new BusinessException(ErrorCode.CONFLICT, "该 Skill 版本已存在");
            }
            SkillVersionPO saved = mapper.selectVersion(value.getId());
            stored = true;
            return saved;
        } finally {
            Files.deleteIfExists(temporary);
            if (!stored) Files.deleteIfExists(target);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "请选择 Skill ZIP 文件");
        if (file.getSize() > MAX_UPLOAD_BYTES) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill ZIP 不能超过 20MB");
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill 文件必须是 ZIP 格式");
    }

    private void validateArchive(Path archive) throws IOException {
        Set<String> entries = new HashSet<>();
        Set<String> topDirectories = new HashSet<>();
        boolean rootManifest = false;
        boolean nestedManifest = false;
        boolean rootFile = false;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill ZIP 文件数量超过 500 个");
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                Path resolved = storageRoot.resolve(name).normalize();
                if (name.isEmpty() || !resolved.startsWith(storageRoot)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill ZIP 包含非法路径");
                String key = name.toLowerCase(Locale.ROOT);
                if (!entries.add(key)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill ZIP 包含重复路径");
                String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                int slash = clean.indexOf('/');
                if (slash > 0) topDirectories.add(clean.substring(0, slash));
                if (slash < 0 && !entry.isDirectory()) rootFile = true;
                if ("SKILL.md".equals(clean)) rootManifest = true;
                if (slash > 0 && clean.indexOf('/', slash + 1) < 0 && clean.endsWith("/SKILL.md")) nestedManifest = true;
            }
        }
        if (!rootManifest && !(nestedManifest && topDirectories.size() == 1 && !rootFile))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill ZIP 根目录或唯一顶层目录中必须包含 SKILL.md");
    }

    private SkillVO toVO(SkillPO skill) {
        List<SkillVersionVO> versions = mapper.selectVersions(skill.getId()).stream().map(SkillVersionVO::new).collect(Collectors.toList());
        return new SkillVO(skill, versions);
    }
    private SkillPO requireSkill(Long id) {
        SkillPO value = mapper.selectSkill(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 不存在");
        return value;
    }
    private SkillVersionPO requireVersion(Long skillId, Long versionId) {
        SkillVersionPO value = mapper.selectVersion(versionId);
        if (value == null || !skillId.equals(value.getSkillId())) throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 版本不存在");
        return value;
    }
    private String validateName(String value) {
        String result = trim(value);
        if (result == null || !result.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill 名称只能包含字母、数字、点、下划线和连字符");
        return result;
    }
    private String validateVersion(String value) {
        String result = trim(value);
        if (result == null || !result.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "版本号只能包含字母、数字、点、下划线和连字符");
        return result;
    }
    private String validateDescription(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 1000) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill 描述不能超过 1000 个字符");
        return result;
    }
    private void validateSkillStatus(String status, boolean blankAllowed) {
        if (blankAllowed && (status == null || status.trim().isEmpty())) return;
        if (!("ENABLED".equals(status) || "DISABLED".equals(status))) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill 状态不正确");
    }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); }
    }
    private String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte part : digest.digest()) value.append(String.format("%02x", part & 0xff));
        return value.toString();
    }
}
