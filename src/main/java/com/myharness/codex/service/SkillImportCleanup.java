package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.po.SkillImportStatePO;
import com.myharness.codex.mapper.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;

/** Only reclaims service-owned files with no live staging or immutable version reference. */
@Component
public class SkillImportCleanup {
    private final SkillMapper skills;
    private final SkillImportMapper records;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Path root;
    public SkillImportCleanup(SkillMapper skills, SkillImportMapper records, TransactionTemplate tx, ObjectMapper json, AgentProperties properties) {
        this.skills=skills; this.records=records; this.tx=tx; this.json=json;
        root=Path.of(properties.getSkillStorageDir()).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelayString="${harness.skill-import.cleanup-ms:600000}", initialDelayString="${harness.skill-import.cleanup-ms:600000}")
    public void cleanup() {
        tx.executeWithoutResult(status -> {
            skills.lockCatalog();
            for (var record : records.expired(LocalDateTime.now())) {
                try {
                    if (record.kind().equals("UPLOAD")) {
                        var upload=json.readValue(record.payload(),SkillImportStatePO.Upload.class);
                        Path path=Path.of(upload.path()).toAbsolutePath().normalize();
                        if (root.equals(path.getParent()) && records.references(path.toString())==0) Files.deleteIfExists(path);
                    }
                    records.delete(record.id());
                } catch (Exception error) { LoggerFactory.getLogger(getClass()).warn("Could not clean expired Skill import {}",record.id(),error); }
            }
        });
        sweep(root);
    }

    private void sweep(Path directory) {
        if (!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths=Files.list(directory)) {
            paths.filter(this::ownedArchive).forEach(path -> {
                try {
                    tx.executeWithoutResult(status -> {
                        skills.lockCatalog();
                        if (records.stagedAtPath(path.toString())==0 && records.references(path.toString())==0) {
                            try {
                                if (Files.getLastModifiedTime(path).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1))))
                                    Files.deleteIfExists(path);
                            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                        }
                    });
                } catch (Exception error) { LoggerFactory.getLogger(getClass()).warn("Could not reclaim Skill archive",error); }
            });
        } catch (java.io.IOException error) { LoggerFactory.getLogger(getClass()).warn("Could not scan Skill storage",error); }
    }

    private boolean ownedArchive(Path path) {
        return Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && path.getFileName().toString().matches("[a-f0-9-]{36}\\.zip");
    }
}
