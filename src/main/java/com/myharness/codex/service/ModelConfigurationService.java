package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ModelConfigurationMapper;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.ModelSecretCipher;
import com.myharness.codex.security.SecureDigests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Owns provider versions, credentials, device routing and immutable runtime snapshots. */
@Service
public class ModelConfigurationService {
    private static final String MASK="******";
    private static final String LOCAL_CODEX="LOCAL_CODEX";
    private static final String MANAGED_PROVIDER="MANAGED_PROVIDER";
    private static final Pattern CODE=Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern MODEL=Pattern.compile("[A-Za-z0-9._:/-]{1,128}");
    private final ModelConfigurationMapper mapper; private final AgentDeviceMapper devices;
    private final AuthorizationService access; private final TransactionTemplate tx; private final ObjectMapper json;
    private final ModelSecretCipher cipher;
    public ModelConfigurationService(ModelConfigurationMapper mapper,AgentDeviceMapper devices,AuthorizationService access,
        TransactionTemplate tx,ObjectMapper json,ModelSecretCipher cipher){this.mapper=mapper;this.devices=devices;this.access=access;this.tx=tx;this.json=json;this.cipher=cipher;}

    public List<ModelConfigurationVO> list(String keyword,String status,Long userId){access.requirePermission(userId,"model:manage");String s=trim(status).toUpperCase(Locale.ROOT);if(!s.isEmpty()&&!Set.of("ENABLED","DISABLED").contains(s))throw conflict("模型配置状态不正确");return mapper.list(trim(keyword),s).stream().map(this::view).toList();}
    public ModelConfigurationVO create(ModelConfigurationDTO input,Long userId){access.requirePermission(userId,"model:manage");ModelRuntimeSpecDTO spec=normalize(input);return tx.execute(s->{if(mapper.codeExists(input.getConfigurationCode(),null)>0)throw conflict("模型配置编码已存在");ModelConfigurationPO c=new ModelConfigurationPO();c.setConfigurationCode(input.getConfigurationCode());c.setName(input.getName().trim());c.setDescription(trim(input.getDescription()));c.setCreatedBy(userId);mapper.insert(c);ModelConfigurationVersionPO v=version(c,1L,spec,input.getApiKey());mapper.insertVersion(v);mapper.setInitialVersion(c.getId(),v.getId());return view(required(mapper.get(c.getId())));});}
    public ModelConfigurationVO update(Long id,ModelConfigurationDTO input,Long userId){access.requirePermission(userId,"model:manage");ModelRuntimeSpecDTO spec=normalize(input);return tx.execute(s->{ModelConfigurationPO c=required(mapper.lock(id));revision(c.getRevision(),input.getRevision());if(mapper.codeExists(input.getConfigurationCode(),id)>0)throw conflict("模型配置编码已存在");c.setConfigurationCode(input.getConfigurationCode());c.setName(input.getName().trim());c.setDescription(trim(input.getDescription()));ModelConfigurationVersionPO v=version(c,mapper.nextVersion(id),spec,input.getApiKey());mapper.insertVersion(v);c.setCurrentVersionId(v.getId());mapper.update(c);return view(required(mapper.get(id)));});}
    public ModelConfigurationVO status(Long id,ModelStatusDTO input,Long userId){access.requirePermission(userId,"model:manage");String value=trim(input.getStatus()).toUpperCase(Locale.ROOT);if(!Set.of("ENABLED","DISABLED").contains(value))throw conflict("模型配置状态不正确");return tx.execute(s->{ModelConfigurationPO c=required(mapper.lock(id));revision(c.getRevision(),input.getRevision());mapper.status(id,value);return view(required(mapper.get(id)));});}
    public List<ModelConfigurationVersionVO> versions(Long id,Long userId){access.requirePermission(userId,"model:manage");required(mapper.get(id));return mapper.versions(id).stream().map(this::versionView).toList();}
    public ModelConfigurationVersionVO revoke(Long id,Long versionId,Long userId){access.requirePermission(userId,"model:manage");return tx.execute(s->{required(mapper.lock(id));ModelConfigurationVersionPO v=mapper.version(versionId);if(v==null||!Objects.equals(v.getModelConfigurationId(),id))throw missing();if("ACTIVE".equals(v.getStatus())&&mapper.revoke(id,versionId)!=1)throw conflict("模型版本已发生变化");return versionView(mapper.version(versionId));});}
    public List<ModelSelectableVersionVO> selectableVersions(Long userId){access.requirePermission(userId,"model:manage");return mapper.selectableVersions().stream().map(v->{ModelRuntimeSpecDTO s=read(v.getRuntimeSpec());return new ModelSelectableVersionVO(v.getModelConfigurationId(),v.getId(),v.getVersionNo(),v.getConfigurationCode(),v.getName(),s.getProviderName(),s.getModelId(),s.getInputModalities(),v.getConfigDigest());}).toList();}
    public DeviceModelAssignmentVO assignment(Long deviceId,Long userId){access.requirePermission(userId,"model:manage");requireDevice(deviceId);DeviceModelAssignmentPO value=mapper.assignment(deviceId);return value==null?null:new DeviceModelAssignmentVO(value);}
    public DeviceModelAssignmentVO assign(Long deviceId,DeviceModelAssignmentDTO input,Long userId){access.requirePermission(userId,"model:manage");return tx.execute(s->{
        AgentDevicePO device=requireDevice(deviceId);
        if(!Boolean.TRUE.equals(device.getModelRuntimeTargets()))throw conflict("请升级 Agent 以支持显式模型运行目标");
        String mode=trim(input.getRuntimeMode()).toUpperCase(Locale.ROOT);
        Long versionId=input.getModelConfigurationVersionId();
        if(LOCAL_CODEX.equals(mode)) {
            if(versionId!=null)throw conflict("本地 Codex 运行目标不能绑定第三方模型版本");
        } else if(MANAGED_PROVIDER.equals(mode)) {
            if(!Boolean.TRUE.equals(device.getManagedModels()))throw conflict("请升级 Agent 以支持托管模型");
            if(versionId==null)throw conflict("第三方模型运行目标必须选择模型版本");
            activeVersion(versionId);
        } else throw conflict("模型运行目标不正确");
        DeviceModelAssignmentPO old=mapper.lockAssignment(deviceId);revision(old==null?0L:old.getRevision(),input.getRevision());
        mapper.assign(deviceId,mode,versionId,userId);return new DeviceModelAssignmentVO(mapper.assignment(deviceId));
    });}
    public void unassign(Long deviceId,Long expectedRevision,Long userId){access.requirePermission(userId,"model:manage");tx.executeWithoutResult(s->{requireDevice(deviceId);DeviceModelAssignmentPO old=mapper.lockAssignment(deviceId);if(old==null)return;revision(old.getRevision(),expectedRevision);mapper.unassign(deviceId);});}

    public ModelRuntimeDTO runtimeForDevice(Long deviceId){DeviceModelAssignmentPO a=mapper.lockAssignment(deviceId);if(a==null)throw conflict("该 Device 尚未配置模型运行目标，执行已阻止");AgentDevicePO device=requireDevice(deviceId);if(!Boolean.TRUE.equals(device.getModelRuntimeTargets()))throw conflict("请升级 Agent 以支持显式模型运行目标");if(LOCAL_CODEX.equals(a.getRuntimeMode()))return localRuntime();if(MANAGED_PROVIDER.equals(a.getRuntimeMode()))return runtime(activeVersion(a.getModelConfigurationVersionId()));throw conflict("Device 模型运行目标无效，执行已阻止");}
    /** A committed Turn owns its exact version even if an administrator disables it before dispatch. */
    public ModelRuntimeDTO runtimeForVersion(Long versionId){ModelConfigurationVersionPO v=versionId==null?null:mapper.version(versionId);if(v==null)throw missing();return runtime(v);}
    /** Rehydrates the credential for the exact managed version frozen by a Turn; local targets stay secret-free. */
    public ModelRuntimeDTO runtimeForSnapshot(String value){ModelRuntimeDTO snapshot=readSnapshot(value);if(snapshot==null)throw conflict("Turn 缺少模型运行快照");if(LOCAL_CODEX.equals(snapshot.getRuntimeMode()))return snapshot;if(!MANAGED_PROVIDER.equals(snapshot.getRuntimeMode()))throw conflict("Turn 模型运行快照无效");ModelRuntimeDTO current=runtimeForVersion(snapshot.getConfigurationVersionId());if(!Objects.equals(snapshot.getRuntimeKey(),current.getRuntimeKey()))throw conflict("Turn 模型运行快照与配置版本不一致");return current;}
    public String snapshot(ModelRuntimeDTO runtime){ModelRuntimeDTO copy=runtime(runtime);copy.setApiKey(null);return write(copy);}
    public ModelRuntimeDTO readSnapshot(String value){if(value==null)return null;try{return json.readValue(value,ModelRuntimeDTO.class);}catch(Exception e){throw new IllegalStateException("Invalid model runtime snapshot",e);}}

    private ModelConfigurationVersionPO activeVersion(Long id){ModelConfigurationVersionPO v=id==null?null:mapper.version(id);if(v==null||!"ACTIVE".equals(v.getStatus())||!"ENABLED".equals(v.getConfigurationStatus()))throw conflict("模型配置版本不存在、已撤销或已停用");return v;}
    private ModelRuntimeDTO runtime(ModelConfigurationVersionPO v){ModelRuntimeSpecDTO s=read(v.getRuntimeSpec());ModelRuntimeDTO r=new ModelRuntimeDTO();copy(s,r);r.setRuntimeMode(MANAGED_PROVIDER);r.setConfigurationId(v.getModelConfigurationId());r.setConfigurationVersionId(v.getId());r.setVersionNo(v.getVersionNo());r.setConfigurationCode(v.getConfigurationCode());r.setName(v.getName());r.setConfigDigest(v.getConfigDigest());r.setRuntimeKey(SecureDigests.sha256(v.getId()+":"+v.getConfigDigest()));r.setApiKey(cipher.decrypt(v.getEncryptedApiKey()));return r;}
    private ModelRuntimeDTO localRuntime(){ModelRuntimeDTO r=new ModelRuntimeDTO();r.setRuntimeMode(LOCAL_CODEX);r.setName("本地 Codex");r.setProviderName("Local Codex");r.setRuntimeKey(SecureDigests.sha256("MODEL_RUNTIME_TARGET:LOCAL_CODEX:V2"));return r;}
    private ModelRuntimeDTO runtime(ModelRuntimeDTO source){ModelRuntimeDTO r=new ModelRuntimeDTO();copy(source,r);r.setSchemaVersion(source.getSchemaVersion());r.setRuntimeMode(source.getRuntimeMode());r.setConfigurationId(source.getConfigurationId());r.setConfigurationVersionId(source.getConfigurationVersionId());r.setVersionNo(source.getVersionNo());r.setConfigurationCode(source.getConfigurationCode());r.setName(source.getName());r.setConfigDigest(source.getConfigDigest());r.setRuntimeKey(source.getRuntimeKey());r.setApiKey(source.getApiKey());return r;}
    private ModelConfigurationVersionPO version(ModelConfigurationPO c,Long no,ModelRuntimeSpecDTO spec,String apiKey){String serialized=write(spec);ModelConfigurationVersionPO v=new ModelConfigurationVersionPO();v.setModelConfigurationId(c.getId());v.setVersionNo(no);v.setConfigurationCode(c.getConfigurationCode());v.setName(c.getName());v.setDescription(c.getDescription());v.setRuntimeSpec(serialized);v.setEncryptedApiKey(cipher.encrypt(apiKey.trim()));v.setConfigDigest(SecureDigests.sha256(serialized));return v;}
    private ModelRuntimeSpecDTO normalize(ModelConfigurationDTO input){if(input==null||trim(input.getName()).isEmpty()||!CODE.matcher(trim(input.getConfigurationCode())).matches())throw conflict("模型名称或配置编码不正确");input.setConfigurationCode(trim(input.getConfigurationCode()));ModelRuntimeSpecDTO r=new ModelRuntimeSpecDTO();r.setProviderName(trim(input.getProviderName()));r.setBaseUrl(trim(input.getBaseUrl()));r.setModelId(trim(input.getModelId()));r.setContextWindowTokens(input.getContextWindowTokens());if(r.getProviderName().isEmpty()||r.getProviderName().length()>128||!MODEL.matcher(r.getModelId()).matches())throw conflict("Provider 名称或模型 ID 不正确");if(r.getContextWindowTokens()<1024||r.getContextWindowTokens()>2000000)throw conflict("上下文窗口必须在 1024 到 2000000 Token 之间");try{URI u=URI.create(r.getBaseUrl());boolean loopback="http".equals(u.getScheme())&&Set.of("localhost","127.0.0.1","::1").contains(u.getHost());if(!"https".equals(u.getScheme())&&!loopback)throw new IllegalArgumentException();}catch(Exception e){throw conflict("Base URL 必须使用 HTTPS（本机回环地址可用 HTTP）");}List<String> modalities=input.getInputModalities()==null?List.of():input.getInputModalities().stream().map(v->trim(v).toUpperCase(Locale.ROOT)).distinct().toList();if(modalities.isEmpty()||!modalities.contains("TEXT")||modalities.stream().anyMatch(v->!Set.of("TEXT","IMAGE").contains(v)))throw conflict("输入模态必须包含 TEXT，且仅支持 TEXT/IMAGE");r.setInputModalities(modalities);return r;}
    private void copy(ModelRuntimeSpecDTO a,ModelRuntimeSpecDTO b){b.setProviderName(a.getProviderName());b.setBaseUrl(a.getBaseUrl());b.setModelId(a.getModelId());b.setInputModalities(a.getInputModalities());b.setContextWindowTokens(a.getContextWindowTokens());}
    private ModelConfigurationVO view(ModelConfigurationPO c){return new ModelConfigurationVO(c.getId(),c.getConfigurationCode(),c.getName(),c.getDescription(),c.getStatus(),c.getCurrentVersionId(),c.getCurrentVersionNo(),c.getConfigDigest(),c.getRevision(),read(c.getRuntimeSpec()),MASK,c.getCreatedAt(),c.getUpdatedAt());}
    private ModelConfigurationVersionVO versionView(ModelConfigurationVersionPO v){return new ModelConfigurationVersionVO(v.getId(),v.getModelConfigurationId(),v.getVersionNo(),v.getConfigurationCode(),v.getName(),v.getDescription(),v.getStatus(),v.getConfigDigest(),read(v.getRuntimeSpec()),MASK,v.getCreatedAt());}
    private AgentDevicePO requireDevice(Long id){AgentDevicePO d=devices.selectById(id);if(d==null||"DISABLED".equals(d.getStatus()))throw new BusinessException(ErrorCode.NOT_FOUND,"设备不存在或已禁用");return d;}
    private ModelConfigurationPO required(ModelConfigurationPO v){if(v==null)throw missing();return v;} private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("Invalid model runtime",e);}} private ModelRuntimeSpecDTO read(String v){if(v==null)return null;try{return json.readValue(v,ModelRuntimeSpecDTO.class);}catch(Exception e){throw new IllegalStateException("Invalid model runtime",e);}}
    private String trim(String v){return v==null?"":v.trim();} private void revision(Long actual,Long expected){if(!Objects.equals(actual,expected))throw conflict("配置已变更，请刷新后重试");} private BusinessException conflict(String m){return new BusinessException(ErrorCode.CONFLICT,m);} private BusinessException missing(){return new BusinessException(ErrorCode.NOT_FOUND,"模型配置不存在");}
}
