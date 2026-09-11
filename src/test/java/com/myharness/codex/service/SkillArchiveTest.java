package com.myharness.codex.service;

import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class SkillArchiveTest {
    @TempDir Path directory;
    @Test void readsWrappedManifestAndYamlMultilineDescription() throws Exception {
        Path file=archive(new String[]{"review/SKILL.md","review/scripts/check.txt"},new String[]{"---\nname: code-review\ndescription: |\n  Review changes\n  carefully\n---\n# Skill","data"});
        var metadata=SkillArchive.inspect(file);
        assertThat(metadata.name()).isEqualTo("code-review"); assertThat(metadata.description()).isEqualTo("Review changes\ncarefully");
    }
    @Test void missingOrMalformedOptionalMetadataAllowsManualConfiguration() throws Exception {
        assertThat(SkillArchive.inspect(archive(new String[]{"SKILL.md"},new String[]{"# Skill"})).name()).isEmpty();
        assertThat(SkillArchive.inspect(archive(new String[]{"SKILL.md"},new String[]{"---\nname: !!java/object malicious\n---"})).name()).isEmpty();
    }
    @Test void rejectsTraversalDuplicatePathsAndMissingManifest() throws Exception {
        for(String path:new String[]{"../escape","C:/escape","/escape","a/../../escape"}) {
            Path file=archive(new String[]{"SKILL.md",path},new String[]{"# Skill","x"});
            assertThatThrownBy(() -> SkillArchive.inspect(file)).isInstanceOf(BusinessException.class).hasMessageContaining("非法路径");
        }
        Path duplicate=archive(new String[]{"SKILL.md","skill.MD"},new String[]{"x","x"});
        assertThatThrownBy(() -> SkillArchive.inspect(duplicate)).hasMessageContaining("重复路径");
        Path missing=archive(new String[]{"docs/SKILL.md","other/file"},new String[]{"x","x"});
        assertThatThrownBy(() -> SkillArchive.inspect(missing)).hasMessageContaining("必须包含 SKILL.md");
    }
    @Test void rejectsExpandedPayloadBeyondLimitEvenWhenZipIsSmall() throws Exception {
        Path file=directory.resolve("expanded.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("SKILL.md")); zip.write("# Skill".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payload"));
            byte[] block=new byte[1024*1024]; for(int i=0;i<101;i++) zip.write(block);
            zip.closeEntry();
        }
        assertThat(Files.size(file)).isLessThan(SkillArchive.MAX_BYTES);
        assertThatThrownBy(() -> SkillArchive.inspect(file)).hasMessageContaining("100MB");
    }
    private Path archive(String[] names,String[] contents) throws Exception {
        Path file=Files.createTempFile(directory,"archive-",".zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
            for(int i=0;i<names.length;i++) { zip.putNextEntry(new ZipEntry(names[i])); zip.write(contents[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        return file;
    }
}
