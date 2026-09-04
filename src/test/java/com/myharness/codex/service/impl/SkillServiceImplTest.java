package com.myharness.codex.service.impl;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SkillPO;
import com.myharness.codex.entity.po.SkillVersionPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SkillMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillServiceImplTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldCreateSkillAndStoreVerifiedArchive() throws Exception {
        SkillMapper mapper = mock(SkillMapper.class);
        SkillPO storedSkill = skill(7L, "code-review");
        doAnswer(invocation -> { ((SkillPO) invocation.getArgument(0)).setId(7L); return 1; }).when(mapper).insertSkill(any(SkillPO.class));
        doAnswer(invocation -> { ((SkillVersionPO) invocation.getArgument(0)).setId(11L); return 1; }).when(mapper).insertVersion(any(SkillVersionPO.class));
        when(mapper.selectSkill(7L)).thenReturn(storedSkill);
        when(mapper.selectVersions(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectVersion(11L)).thenAnswer(invocation -> {
            SkillVersionPO version = new SkillVersionPO(); version.setId(11L); version.setSkillId(7L); version.setVersion("1.0.0");
            version.setSha256(new String(new char[64]).replace('\0', 'a')); version.setFileSize(1L); version.setStatus("ACTIVE"); return version;
        });
        SkillServiceImpl service = service(mapper);

        service.create("code-review", "review changes", "1.0.0", upload("SKILL.md", "# Review"), 3L);

        verify(mapper).insertVersion(any(SkillVersionPO.class));
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void shouldRejectArchiveWithoutRootSkillManifest() throws Exception {
        SkillMapper mapper = mock(SkillMapper.class);
        when(mapper.selectSkill(7L)).thenReturn(skill(7L, "demo"));
        SkillServiceImpl service = service(mapper);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.uploadVersion(7L, "1.0.0", upload("docs/readme.md", "missing"), 3L));

        assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());
        verify(mapper, never()).insertVersion(any(SkillVersionPO.class));
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(Files::isRegularFile));
        }
    }

    private SkillServiceImpl service(SkillMapper mapper) {
        AgentProperties properties = new AgentProperties(); properties.setSkillStorageDir(temporaryDirectory.toString());
        return new SkillServiceImpl(mapper, properties);
    }
    private SkillPO skill(Long id, String name) {
        SkillPO value = new SkillPO(); value.setId(id); value.setSkillName(name); value.setDescription(""); value.setStatus("ENABLED");
        value.setVersionCount(0); value.setCreatedAt(LocalDateTime.now()); value.setUpdatedAt(LocalDateTime.now()); return value;
    }
    private MockMultipartFile upload(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        return new MockMultipartFile("file", "skill.zip", "application/zip", bytes.toByteArray());
    }
}
