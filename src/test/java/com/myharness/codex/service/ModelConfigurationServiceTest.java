package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.entity.dto.ModelConfigurationDTO;
import com.myharness.codex.entity.dto.DeviceModelAssignmentDTO;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.DeviceModelAssignmentPO;
import com.myharness.codex.entity.po.ModelConfigurationPO;
import com.myharness.codex.entity.po.ModelConfigurationVersionPO;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ModelConfigurationMapper;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.ModelSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelConfigurationServiceTest {
    @Test void explicitlyAssignsLocalCodexWithoutAProviderVersionAndProducesASecretFreeV2Target() {
        ModelConfigurationMapper mapper=mock(ModelConfigurationMapper.class);AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
        TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        AgentDevicePO device=new AgentDevicePO();device.setId(3L);device.setStatus("ONLINE");device.setModelRuntimeTargets(true);
        when(devices.selectById(3L)).thenReturn(device);
        DeviceModelAssignmentPO assigned=new DeviceModelAssignmentPO();assigned.setDeviceId(3L);assigned.setRuntimeMode("LOCAL_CODEX");assigned.setRevision(0L);
        when(mapper.assignment(3L)).thenReturn(assigned);
        ModelConfigurationService service=new ModelConfigurationService(mapper,devices,mock(AuthorizationService.class),tx,new ObjectMapper(),cipher());
        DeviceModelAssignmentDTO input=new DeviceModelAssignmentDTO();input.setRuntimeMode("LOCAL_CODEX");input.setRevision(0L);

        var view=service.assign(3L,input,7L);
        when(mapper.lockAssignment(3L)).thenReturn(assigned);
        var runtime=service.runtimeForDevice(3L);

        assertEquals("LOCAL_CODEX",view.runtimeMode());assertNull(view.modelConfigurationVersionId());
        assertEquals(2,runtime.getSchemaVersion());assertEquals("LOCAL_CODEX",runtime.getRuntimeMode());
        assertNull(runtime.getModelId());assertNull(runtime.getApiKey());assertTrue(runtime.getRuntimeKey().matches("[0-9a-f]{64}"));
        verify(mapper).assign(3L,"LOCAL_CODEX",null,7L);
        assertNull(service.runtimeForSnapshot(service.snapshot(runtime)).getApiKey());
    }

    @Test void rejectsImplicitLocalFallbackWhenNoAssignmentExists() {
        AgentDeviceMapper devices=mock(AgentDeviceMapper.class);ModelConfigurationMapper mapper=mock(ModelConfigurationMapper.class);
        ModelConfigurationService service=new ModelConfigurationService(mapper,devices,mock(AuthorizationService.class),mock(TransactionTemplate.class),new ObjectMapper(),cipher());
        var failure=assertThrows(com.myharness.codex.exception.BusinessException.class,()->service.runtimeForDevice(9L));
        assertTrue(failure.getMessage().contains("执行已阻止"));
    }

    @Test void publishesEncryptedImmutableVersionAndSecretFreeSnapshot() {
        ModelConfigurationMapper mapper=mock(ModelConfigurationMapper.class);ObjectMapper json=new ObjectMapper();
        TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        ModelSecretCipher cipher=cipher();AtomicReference<ModelConfigurationVersionPO> stored=new AtomicReference<>();
        doAnswer(call->{((ModelConfigurationPO)call.getArgument(0)).setId(1L);return 1;}).when(mapper).insert(any());
        doAnswer(call->{ModelConfigurationVersionPO v=call.getArgument(0);v.setId(10L);stored.set(v);return 1;}).when(mapper).insertVersion(any());
        when(mapper.get(1L)).thenAnswer(call->{ModelConfigurationVersionPO v=stored.get();ModelConfigurationPO c=new ModelConfigurationPO();c.setId(1L);c.setConfigurationCode("deepseek_v4");c.setName("DeepSeek V4");c.setDescription("");c.setStatus("ENABLED");c.setCurrentVersionId(10L);c.setCurrentVersionNo(1L);c.setRevision(0L);c.setRuntimeSpec(v.getRuntimeSpec());c.setConfigDigest(v.getConfigDigest());return c;});
        ModelConfigurationService service=new ModelConfigurationService(mapper,mock(AgentDeviceMapper.class),mock(AuthorizationService.class),tx,json,cipher);
        ModelConfigurationDTO input=new ModelConfigurationDTO();input.setConfigurationCode("deepseek_v4");input.setName("DeepSeek V4");input.setProviderName("DeepSeek");input.setBaseUrl("https://api.example.com/v1");input.setModelId("DeepSeek-V4-Flash-Vision-Exp");input.setInputModalities(List.of("text","image"));input.setContextWindowTokens(256000);input.setApiKey("sk-literal-secret");

        var created=service.create(input,7L);

        assertEquals("******",created.apiKeyMasked());assertEquals(256000,created.runtime().getContextWindowTokens());assertFalse(stored.get().getRuntimeSpec().contains("sk-literal-secret"));assertFalse(stored.get().getEncryptedApiKey().contains("sk-literal-secret"));
        ModelConfigurationVersionPO version=stored.get();version.setStatus("ACTIVE");version.setConfigurationStatus("ENABLED");when(mapper.version(10L)).thenReturn(version);
        var runtime=service.runtimeForVersion(10L);assertEquals(2,runtime.getSchemaVersion());assertEquals("MANAGED_PROVIDER",runtime.getRuntimeMode());assertEquals("sk-literal-secret",runtime.getApiKey());assertEquals(256000,runtime.getContextWindowTokens());assertFalse(service.snapshot(runtime).contains("sk-literal-secret"));
        assertEquals("sk-literal-secret",service.runtimeForSnapshot(service.snapshot(runtime)).getApiKey());
    }

    private static ModelSecretCipher cipher(){SecurityProperties security=new SecurityProperties();security.setModelSecretKey("model-test-encryption-key");return new ModelSecretCipher(security);}
}
