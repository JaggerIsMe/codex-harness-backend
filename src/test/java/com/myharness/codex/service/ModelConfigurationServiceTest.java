package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.entity.dto.ModelConfigurationDTO;
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
    @Test void publishesEncryptedImmutableVersionAndSecretFreeSnapshot() {
        ModelConfigurationMapper mapper=mock(ModelConfigurationMapper.class);ObjectMapper json=new ObjectMapper();
        TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        SecurityProperties security=new SecurityProperties();security.setModelSecretKey("model-test-encryption-key");
        ModelSecretCipher cipher=new ModelSecretCipher(security);AtomicReference<ModelConfigurationVersionPO> stored=new AtomicReference<>();
        doAnswer(call->{((ModelConfigurationPO)call.getArgument(0)).setId(1L);return 1;}).when(mapper).insert(any());
        doAnswer(call->{ModelConfigurationVersionPO v=call.getArgument(0);v.setId(10L);stored.set(v);return 1;}).when(mapper).insertVersion(any());
        when(mapper.get(1L)).thenAnswer(call->{ModelConfigurationVersionPO v=stored.get();ModelConfigurationPO c=new ModelConfigurationPO();c.setId(1L);c.setConfigurationCode("deepseek_v4");c.setName("DeepSeek V4");c.setDescription("");c.setStatus("ENABLED");c.setCurrentVersionId(10L);c.setCurrentVersionNo(1L);c.setRevision(0L);c.setRuntimeSpec(v.getRuntimeSpec());c.setConfigDigest(v.getConfigDigest());return c;});
        ModelConfigurationService service=new ModelConfigurationService(mapper,mock(AgentDeviceMapper.class),mock(AuthorizationService.class),tx,json,cipher);
        ModelConfigurationDTO input=new ModelConfigurationDTO();input.setConfigurationCode("deepseek_v4");input.setName("DeepSeek V4");input.setProviderName("DeepSeek");input.setBaseUrl("https://api.example.com/v1");input.setModelId("DeepSeek-V4-Flash-Vision-Exp");input.setInputModalities(List.of("text","image"));input.setContextWindowTokens(256000);input.setApiKey("sk-literal-secret");

        var created=service.create(input,7L);

        assertEquals("******",created.apiKeyMasked());assertEquals(256000,created.runtime().getContextWindowTokens());assertFalse(stored.get().getRuntimeSpec().contains("sk-literal-secret"));assertFalse(stored.get().getEncryptedApiKey().contains("sk-literal-secret"));
        ModelConfigurationVersionPO version=stored.get();version.setStatus("ACTIVE");version.setConfigurationStatus("ENABLED");when(mapper.version(10L)).thenReturn(version);
        var runtime=service.runtimeForVersion(10L);assertEquals("sk-literal-secret",runtime.getApiKey());assertEquals(256000,runtime.getContextWindowTokens());assertFalse(service.snapshot(runtime).contains("sk-literal-secret"));
    }
}
