package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.McpConfigurationDTO;
import com.myharness.codex.entity.po.McpConfigurationPO;
import com.myharness.codex.entity.po.McpConfigurationVersionPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.McpConfigurationMapper;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpConfigurationServiceTest {
    McpConfigurationMapper mapper;
    McpConfigurationService service;
    ObjectMapper json;

    @BeforeEach void setup() {
        mapper=mock(McpConfigurationMapper.class);json=new ObjectMapper();
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        service=new McpConfigurationService(mapper,mock(AuthorizationService.class),tx,json);
    }

    @Test void createStoresAnImmutableNormalizedVersionAndDigest() {
        AtomicReference<McpConfigurationVersionPO> stored=new AtomicReference<>();
        doAnswer(call -> {((McpConfigurationPO)call.getArgument(0)).setId(1L);return 1;}).when(mapper).insert(any());
        doAnswer(call -> {var version=(McpConfigurationVersionPO)call.getArgument(0);version.setId(10L);stored.set(version);return 1;}).when(mapper).insertVersion(any());
        when(mapper.get(1L)).thenAnswer(call -> {
            var version=stored.get();var value=new McpConfigurationPO();value.setId(1L);value.setServerCode("github");value.setName("GitHub");value.setDescription("");
            value.setStatus("ENABLED");value.setCurrentVersionId(10L);value.setCurrentVersionNo(1L);value.setRevision(0L);
            value.setRuntimeSpec(version.getRuntimeSpec());value.setConfigDigest(version.getConfigDigest());return value;
        });
        McpConfigurationDTO input=stdio();

        var created=service.create(input,7L);

        assertEquals(10L,created.currentVersionId());assertEquals(64,created.configDigest().length());
        assertEquals("STDIO",created.runtimeSpec().getTransportType());assertEquals(List.of("TOKEN"),created.runtimeSpec().getEnvVars());
        assertFalse(stored.get().getRuntimeSpec().contains("secret-value"));verify(mapper).setInitialVersion(1L,10L);
    }

    @Test void runtimeRejectsDisabledConfigurationAndReturnsOnlyEnvironmentVariableNames() {
        var version=new McpConfigurationVersionPO();version.setId(10L);version.setMcpConfigurationId(1L);version.setVersionNo(2L);
        version.setServerCode("github");version.setName("GitHub");version.setVersionStatus("ACTIVE");version.setConfigurationStatus("ENABLED");
        version.setConfigDigest("a".repeat(64));
        try {version.setRuntimeSpec(json.writeValueAsString(runtimeSpec()));} catch(Exception e){throw new AssertionError(e);}
        when(mapper.version(10L)).thenReturn(version);

        var runtime=service.runtimes(List.of(10L)).getFirst();

        assertEquals(List.of("TOKEN"),runtime.getEnvVars());assertEquals(10L,runtime.getConfigurationVersionId());
        version.setConfigurationStatus("DISABLED");
        assertThrows(BusinessException.class,()->service.runtimes(List.of(10L)));
    }

    @Test void storesLiteralHttpHeaderButMasksItFromManagementResponses() throws Exception {
        AtomicReference<McpConfigurationVersionPO> stored=new AtomicReference<>();
        doAnswer(call -> {((McpConfigurationPO)call.getArgument(0)).setId(2L);return 1;}).when(mapper).insert(any());
        doAnswer(call -> {var version=(McpConfigurationVersionPO)call.getArgument(0);version.setId(20L);stored.set(version);return 1;}).when(mapper).insertVersion(any());
        when(mapper.get(2L)).thenAnswer(call -> {
            var version=stored.get();var value=new McpConfigurationPO();value.setId(2L);value.setServerCode("lingxing");value.setName("领星 MCP");value.setDescription("");
            value.setStatus("ENABLED");value.setCurrentVersionId(20L);value.setCurrentVersionNo(1L);value.setRevision(0L);
            value.setRuntimeSpec(version.getRuntimeSpec());value.setConfigDigest(version.getConfigDigest());return value;
        });
        var input=new McpConfigurationDTO();input.setName("领星 MCP");input.setServerCode("lingxing");input.setTransportType("STREAMABLE_HTTP");
        input.setUrl("https://mcp.example.com/mcp");input.setHttpHeaders(Map.of("X-Mcp-Key","literal-secret"));
        input.setStartupTimeoutSeconds(10);input.setToolTimeoutSeconds(60);input.setRequired(true);

        var created=service.create(input,7L);

        assertEquals("******",created.runtimeSpec().getHttpHeaders().get("X-Mcp-Key"));
        assertEquals("literal-secret",json.readTree(stored.get().getRuntimeSpec()).path("httpHeaders").path("X-Mcp-Key").asText());
        assertFalse(stored.get().getRuntimeSpec().contains("envHttpHeaders"));
        var version=stored.get();version.setMcpConfigurationId(2L);version.setVersionNo(1L);version.setServerCode("lingxing");
        version.setName("领星 MCP");version.setVersionStatus("ACTIVE");version.setConfigurationStatus("ENABLED");
        when(mapper.version(20L)).thenReturn(version);
        assertEquals("literal-secret",service.runtimes(List.of(20L)).getFirst().getHttpHeaders().get("X-Mcp-Key"));
    }

    @Test void rejectsOverlappingToolPolicies() {
        var input=stdio();input.setEnabledTools(List.of("search"));input.setDisabledTools(List.of("search"));
        assertThrows(BusinessException.class,()->service.create(input,7L));verify(mapper,never()).insert(any());
    }

    @Test void rejectsRemovedHttpEnvironmentMappingFields() {
        assertThrows(Exception.class,() -> json.readValue("{\"transportType\":\"STREAMABLE_HTTP\",\"envHttpHeaders\":{\"X-Mcp-Key\":\"MCP_KEY\"}}",
                com.myharness.codex.entity.dto.McpRuntimeSpecDTO.class));
        assertThrows(Exception.class,() -> json.readValue("{\"transportType\":\"STREAMABLE_HTTP\",\"bearerTokenEnvVar\":\"MCP_TOKEN\"}",
                com.myharness.codex.entity.dto.McpRuntimeSpecDTO.class));
    }

    private McpConfigurationDTO stdio() {
        var input=new McpConfigurationDTO();input.setName("GitHub");input.setServerCode("github");input.setTransportType("stdio");
        input.setCommand("npx");input.setArgs(List.of("-y","server"));input.setEnvVars(List.of("TOKEN"));input.setCwdMode("WORKSPACE");
        input.setStartupTimeoutSeconds(10);input.setToolTimeoutSeconds(60);input.setRequired(true);return input;
    }
    private com.myharness.codex.entity.dto.McpRuntimeSpecDTO runtimeSpec() {
        var value=new com.myharness.codex.entity.dto.McpRuntimeSpecDTO();value.setTransportType("STDIO");value.setCommand("npx");
        value.setArgs(List.of("-y","server"));value.setEnvVars(List.of("TOKEN"));value.setCwdMode("WORKSPACE");return value;
    }
}
