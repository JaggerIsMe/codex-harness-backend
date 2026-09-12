package com.myharness.codex.service;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** The common ZIP validation used by single and batch uploads. Never executes package content. */
public final class SkillArchive {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 100L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private SkillArchive() {}
    public record Metadata(String name, String description) {}
    public static String tag(String value) {
        if (value == null) return "";
        if (value.length() > 200) throw invalid("Skill 标签不能超过 200 个字符");
        return value.trim();
    }

    public static void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("请选择 Skill ZIP 文件");
        if (file.getSize() > MAX_BYTES) throw invalid("Skill ZIP 不能超过 20MB");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw invalid("Skill 文件必须是 ZIP 格式");
    }

    public static Metadata inspect(Path archive) throws IOException {
        if (Files.size(archive) > MAX_BYTES) throw invalid("Skill ZIP 不能超过 20MB");
        Set<String> paths = new HashSet<>();
        Set<String> tops = new HashSet<>();
        Map<String, String> manifests = new HashMap<>();
        boolean rootFile = false;
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 500) throw invalid("Skill ZIP 文件数量超过 500 个");
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || name.startsWith("/") || name.contains(":") || name.indexOf('\0') >= 0
                        || Arrays.stream(name.split("/")).anyMatch(p -> p.equals("..") || p.equals(".")))
                    throw invalid("Skill ZIP 包含非法路径");
                String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                if (!paths.add(clean.toLowerCase(Locale.ROOT))) throw invalid("Skill ZIP 包含重复路径");
                int slash = clean.indexOf('/');
                if (slash > 0) tops.add(clean.substring(0, slash));
                if (slash < 0 && !entry.isDirectory()) rootFile = true;
                boolean manifest = !entry.isDirectory() && (clean.equals("SKILL.md")
                        || slash > 0 && clean.indexOf('/', slash + 1) < 0 && clean.endsWith("/SKILL.md"));
                ByteArrayOutputStream content = manifest ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > MAX_EXPANDED_BYTES) throw invalid("Skill ZIP 解压内容不能超过 100MB");
                    if (content != null) {
                        if (content.size() + read > MAX_MANIFEST_BYTES) throw invalid("SKILL.md 不能超过 256KB");
                        content.write(buffer, 0, read);
                    }
                }
                if (content != null) manifests.put(clean, content.toString(StandardCharsets.UTF_8));
            }
        } catch (ZipException exception) { throw invalid("Skill ZIP 已损坏"); }
        String manifest = manifests.get("SKILL.md");
        if (manifest == null && tops.size() == 1 && !rootFile) manifest = manifests.get(tops.iterator().next() + "/SKILL.md");
        if (manifest == null) throw invalid("Skill ZIP 根目录或唯一顶层目录中必须包含 SKILL.md");
        return metadata(manifest);
    }

    private static Metadata metadata(String text) {
        String normalized = text.replace("\r\n", "\n").replaceFirst("^\\uFEFF", "");
        if (!normalized.startsWith("---\n")) return new Metadata("", "");
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) return new Metadata("", "");
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(10);
            options.setCodePointLimit(MAX_MANIFEST_BYTES);
            Object value = new Yaml(new SafeConstructor(options)).load(normalized.substring(4, end));
            if (value instanceof Map<?, ?> map) return new Metadata(scalar(map.get("name"), 128), scalar(map.get("description"), 1000));
        } catch (RuntimeException ignored) { /* Metadata is optional; valid ZIPs can be configured manually. */ }
        return new Metadata("", "");
    }
    private static String scalar(Object value, int max) { return value instanceof String s && s.length() <= max ? s.trim() : ""; }
    public static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static String name(String value) { return segment(value,128,"Skill 名称"); }
    public static String version(String value) { return segment(value,64,"版本号"); }
    private static String segment(String value, int max, String label) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || !result.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw invalid(label + "只能包含字母、数字、点、下划线和连字符");
        return result;
    }
    public static String description(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 1000) throw invalid("Skill 描述不能超过 1000 个字符");
        return result;
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_REQUEST, message); }
}
