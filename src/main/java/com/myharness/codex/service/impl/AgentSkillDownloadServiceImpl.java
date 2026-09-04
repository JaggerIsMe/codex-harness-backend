package com.myharness.codex.service.impl;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.SkillDownloadPO;
import com.myharness.codex.entity.vo.SkillFileVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SkillMapper;
import com.myharness.codex.security.DeviceAuthenticationService;
import com.myharness.codex.service.AgentSkillDownloadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class AgentSkillDownloadServiceImpl implements AgentSkillDownloadService {
    private final DeviceAuthenticationService authenticationService;
    private final SkillMapper skillMapper;
    private final AgentProperties properties;
    public AgentSkillDownloadServiceImpl(DeviceAuthenticationService authenticationService,SkillMapper skillMapper,
                                         AgentProperties properties) {
        this.authenticationService=authenticationService; this.skillMapper=skillMapper; this.properties=properties;
    }
    @Override public SkillFileVO download(Long versionId,String deviceCode,String authorization) throws IOException {
        AgentDevicePO device=authenticationService.authenticate(deviceCode,authorization);
        SkillDownloadPO download=skillMapper.selectDownload(versionId,device.getId());
        if (download==null || "DISABLED".equals(download.getVersionStatus()) || "REMOVED".equals(download.getInstallStatus()))
            throw new BusinessException(ErrorCode.NOT_FOUND,"Skill 版本不存在或未下发到该设备");
        Path root=Paths.get(properties.getSkillStorageDir()).toAbsolutePath().normalize();
        Path file=Paths.get(download.getStoragePath()).toAbsolutePath().normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file)!=download.getFileSize())
            throw new BusinessException(ErrorCode.NOT_FOUND,"Skill 文件不存在或已损坏");
        return new SkillFileVO(new FileSystemResource(file.toFile()),download.getFileSize());
    }
}
