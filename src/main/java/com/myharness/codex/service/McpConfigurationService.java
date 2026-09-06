package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.McpConfigurationMapper;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.SecureDigests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class McpConfigurationService {
    private static final String MASKED_SECRET="******";
    private static final Pattern CODE=Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ENV=Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HEADER=Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+(\\-[A-Za-z0-9!#$%&'*+.^_`|~-]+)*");
    private final McpConfigurationMapper mapper;
    private final AuthorizationService access;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public McpConfigurationService(McpConfigurationMapper mapper,AuthorizationService access,
                                   TransactionTemplate tx,ObjectMapper json) {
        this.mapper=mapper;this.access=access;this.tx=tx;this.json=json;
    }

    public List<McpConfigurationVO> list(String keyword,String status,Long userId) {
        access.requirePermission(userId,"mcp:manage");
        String normalizedStatus=trim(status).toUpperCase(Locale.ROOT);
        if(!normalizedStatus.isEmpty() && !Set.of("ENABLED","DISABLED").contains(normalizedStatus)) throw conflict("MCP 配置状态不正确");
        return mapper.list(trim(keyword),normalizedStatus).stream().map(this::view).toList();
    }

    public McpConfigurationVO create(McpConfigurationDTO input,Long userId) {
        access.requirePermission(userId,"mcp:manage");
        McpRuntimeSpecDTO spec=normalize(input);
        return tx.execute(s -> {
            if(mapper.codeExists(input.getServerCode(),null)>0) throw conflict("MCP Server Code 已存在");
            McpConfigurationPO configuration=new McpConfigurationPO();
            configuration.setServerCode(input.getServerCode());configuration.setName(input.getName().trim());
            configuration.setDescription(trim(input.getDescription()));configuration.setCreatedBy(userId);
            mapper.insert(configuration);
            McpConfigurationVersionPO version=version(configuration,1L,spec);
            mapper.insertVersion(version);mapper.setInitialVersion(configuration.getId(),version.getId());
            return view(required(mapper.get(configuration.getId())));
        });
    }

    public McpConfigurationVO update(Long id,McpConfigurationDTO input,Long userId) {
        access.requirePermission(userId,"mcp:manage");
        McpRuntimeSpecDTO spec=normalize(input);
        return tx.execute(s -> {
            McpConfigurationPO configuration=required(mapper.lock(id));revision(configuration.getRevision(),input.getRevision());
            if(mapper.codeExists(input.getServerCode(),id)>0) throw conflict("MCP Server Code 已存在");
            configuration.setServerCode(input.getServerCode());configuration.setName(input.getName().trim());
            configuration.setDescription(trim(input.getDescription()));
            McpConfigurationVersionPO version=version(configuration,mapper.nextVersion(id),spec);
            mapper.insertVersion(version);configuration.setCurrentVersionId(version.getId());mapper.update(configuration);
            return view(required(mapper.get(id)));
        });
    }

    public McpConfigurationVO status(Long id,McpStatusDTO input,Long userId) {
        access.requirePermission(userId,"mcp:manage");
        String status=trim(input.getStatus()).toUpperCase(Locale.ROOT);
        if(!Set.of("ENABLED","DISABLED").contains(status)) throw conflict("MCP 配置状态不正确");
        return tx.execute(s -> {
            McpConfigurationPO configuration=required(mapper.lock(id));revision(configuration.getRevision(),input.getRevision());
            List<Long> projects=mapper.boundProjectsForConfiguration(id);projects.forEach(mapper::lockProject);
            mapper.status(id,status);projects.forEach(mapper::bumpProject);return view(required(mapper.get(id)));
        });
    }

    public List<McpConfigurationVersionVO> versions(Long id,Long userId) {
        access.requirePermission(userId,"mcp:manage");required(mapper.get(id));
        return mapper.versions(id).stream().map(this::versionView).toList();
    }

    public McpConfigurationVersionVO revoke(Long configurationId,Long versionId,Long userId) {
        access.requirePermission(userId,"mcp:manage");
        return tx.execute(s -> {
            McpConfigurationPO configuration=required(mapper.lock(configurationId));
            McpConfigurationVersionPO version=mapper.version(versionId);
            if(version==null || !Objects.equals(version.getMcpConfigurationId(),configurationId)) throw missing();
            if("REVOKED".equals(version.getVersionStatus())) return versionView(version);
            List<Long> projects=mapper.boundProjectsForVersion(versionId);projects.forEach(mapper::lockProject);
            if(mapper.revoke(configurationId,versionId)!=1) throw conflict("MCP 配置版本已发生变化");
            projects.forEach(mapper::bumpProject);
            return versionView(mapper.version(versionId));
        });
    }

    public List<McpSelectableVersionVO> selectableVersions(Long userId) {
        access.requirePermission(userId,"expert:manage");
        return mapper.selectableVersions().stream().map(v -> new McpSelectableVersionVO(v.getMcpConfigurationId(),v.getId(),
                v.getVersionNo(),v.getServerCode(),v.getName(),read(v.getRuntimeSpec()).getTransportType(),v.getConfigDigest())).toList();
    }

    public List<McpRuntimeDTO> runtimes(List<Long> versionIds) {
        if(versionIds==null || versionIds.size()>20 || new HashSet<>(versionIds).size()!=versionIds.size()) throw conflict("MCP 版本列表不正确");
        Set<Long> configurations=new HashSet<>();Set<String> codes=new HashSet<>();List<McpRuntimeDTO> result=new ArrayList<>();
        for(Long id:versionIds) {
            McpConfigurationVersionPO version=id==null ? null : mapper.version(id);
            if(version==null || !"ACTIVE".equals(version.getVersionStatus()) || !"ENABLED".equals(version.getConfigurationStatus()))
                throw conflict("依赖的 MCP 配置版本不存在、已撤销或已停用");
            if(!configurations.add(version.getMcpConfigurationId()) || !codes.add(version.getServerCode()))
                throw conflict("一个专家不能重复绑定同一 MCP Configuration");
            McpRuntimeSpecDTO spec=read(version.getRuntimeSpec());McpRuntimeDTO runtime=new McpRuntimeDTO();copy(spec,runtime);
            runtime.setConfigurationId(version.getMcpConfigurationId());runtime.setConfigurationVersionId(version.getId());
            runtime.setVersionNo(version.getVersionNo());runtime.setServerCode(version.getServerCode());runtime.setName(version.getName());
            runtime.setConfigDigest(version.getConfigDigest());result.add(runtime);
        }
        return List.copyOf(result);
    }

    private McpConfigurationVersionPO version(McpConfigurationPO configuration,Long versionNo,McpRuntimeSpecDTO spec) {
        String serialized=write(spec);McpConfigurationVersionPO value=new McpConfigurationVersionPO();
        value.setMcpConfigurationId(configuration.getId());value.setVersionNo(versionNo);value.setServerCode(configuration.getServerCode());
        value.setName(configuration.getName());value.setDescription(configuration.getDescription());value.setRuntimeSpec(serialized);
        value.setConfigDigest(SecureDigests.sha256(serialized));return value;
    }

    private McpRuntimeSpecDTO normalize(McpConfigurationDTO input) {
        if(input==null || trim(input.getName()).isEmpty() || !CODE.matcher(trim(input.getServerCode())).matches())
            throw conflict("MCP 名称或 Server Code 不正确");
        input.setServerCode(input.getServerCode().trim());
        McpRuntimeSpecDTO result=new McpRuntimeSpecDTO();String transport=trim(input.getTransportType()).toUpperCase(Locale.ROOT);
        if(!Set.of("STDIO","STREAMABLE_HTTP").contains(transport)) throw conflict("MCP transport 只支持 STDIO 或 STREAMABLE_HTTP");
        result.setTransportType(transport);result.setStartupTimeoutSeconds(input.getStartupTimeoutSeconds());
        result.setToolTimeoutSeconds(input.getToolTimeoutSeconds());result.setRequired(input.isRequired());
        if(result.getStartupTimeoutSeconds()<1 || result.getStartupTimeoutSeconds()>120 || result.getToolTimeoutSeconds()<1 || result.getToolTimeoutSeconds()>600)
            throw conflict("MCP 超时时间不正确");
        result.setEnabledTools(strings(input.getEnabledTools(),100,128,"enabledTools"));
        result.setDisabledTools(strings(input.getDisabledTools(),100,128,"disabledTools"));
        if(!Collections.disjoint(result.getEnabledTools(),result.getDisabledTools())) throw conflict("同一工具不能同时启用和禁用");
        if("STDIO".equals(transport)) {
            if(trim(input.getCommand()).isEmpty() || trim(input.getCommand()).length()>1024) throw conflict("STDIO command 不能为空");
            result.setCommand(input.getCommand().trim());result.setArgs(strings(input.getArgs(),100,2000,"args"));
            result.setEnvVars(envNames(input.getEnvVars(),"envVars"));
            String cwd=trim(input.getCwdMode()).toUpperCase(Locale.ROOT);result.setCwdMode(cwd.isEmpty()?null:cwd);
            if(result.getCwdMode()!=null && !"WORKSPACE".equals(result.getCwdMode())) throw conflict("cwdMode 只支持 WORKSPACE");
        } else {
            String url=trim(input.getUrl());
            try { URI uri=URI.create(url);if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null) throw new IllegalArgumentException(); }
            catch(Exception e){throw conflict("Streamable HTTP URL 不正确");}
            result.setUrl(url);
            Map<String,String> headers=new LinkedHashMap<>();
            Set<String> normalizedHeaders=new HashSet<>();
            if(input.getHttpHeaders()!=null) for(var entry:input.getHttpHeaders().entrySet()) {
                String header=trim(entry.getKey()),secret=entry.getValue();
                if(!HEADER.matcher(header).matches() || secret==null || secret.isBlank() || secret.length()>4096
                        || secret.indexOf('\r')>=0 || secret.indexOf('\n')>=0
                        || !normalizedHeaders.add(header.toLowerCase(Locale.ROOT))) throw conflict("HTTP Header 名称或密钥不正确");
                headers.put(header,secret);
            }
            if(headers.size()>30) throw conflict("HTTP Header 数量不能超过 30");result.setHttpHeaders(Map.copyOf(headers));
        }
        return result;
    }

    private List<String> envNames(List<String> values,String field) {List<String> result=strings(values,50,128,field);for(String value:result) envName(value);return result;}
    private String envName(String value) {if(!ENV.matcher(value).matches()) throw conflict("环境变量名不正确");return value;}
    private List<String> strings(List<String> values,int maxCount,int maxLength,String field) {
        if(values==null) return List.of();if(values.size()>maxCount) throw conflict(field+" 数量过多");
        List<String> result=values.stream().map(this::trim).filter(v->!v.isEmpty()).toList();
        if(result.stream().anyMatch(v->v.length()>maxLength) || new HashSet<>(result).size()!=result.size()) throw conflict(field+" 内容不正确");
        return result;
    }
    private void copy(McpRuntimeSpecDTO from,McpRuntimeSpecDTO to) {
        to.setTransportType(from.getTransportType());to.setCommand(from.getCommand());to.setArgs(from.getArgs());to.setCwdMode(from.getCwdMode());
        to.setEnvVars(from.getEnvVars());to.setUrl(from.getUrl());to.setHttpHeaders(from.getHttpHeaders());
        to.setStartupTimeoutSeconds(from.getStartupTimeoutSeconds());
        to.setToolTimeoutSeconds(from.getToolTimeoutSeconds());to.setRequired(from.isRequired());
        to.setEnabledTools(from.getEnabledTools());to.setDisabledTools(from.getDisabledTools());
    }
    private McpConfigurationVO view(McpConfigurationPO value) {return new McpConfigurationVO(value.getId(),value.getServerCode(),value.getName(),value.getDescription(),value.getStatus(),value.getCurrentVersionId(),value.getCurrentVersionNo(),value.getConfigDigest(),value.getRevision(),publicSpec(value.getRuntimeSpec()),value.getCreatedAt(),value.getUpdatedAt());}
    private McpConfigurationVersionVO versionView(McpConfigurationVersionPO value) {return new McpConfigurationVersionVO(value.getId(),value.getMcpConfigurationId(),value.getVersionNo(),value.getServerCode(),value.getName(),value.getDescription(),value.getVersionStatus(),value.getConfigDigest(),publicSpec(value.getRuntimeSpec()),value.getCreatedAt());}
    private McpRuntimeSpecDTO publicSpec(String value) {
        McpRuntimeSpecDTO stored=read(value);if(stored==null) return null;
        McpRuntimeSpecDTO result=new McpRuntimeSpecDTO();copy(stored,result);
        Map<String,String> headers=new LinkedHashMap<>();stored.getHttpHeaders().keySet().forEach(name -> headers.put(name,MASKED_SECRET));
        result.setHttpHeaders(Map.copyOf(headers));return result;
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Invalid MCP runtime",e);}}
    private McpRuntimeSpecDTO read(String value){if(value==null) return null;try{return json.readValue(value,McpRuntimeSpecDTO.class);}catch(Exception e){throw new IllegalStateException("Invalid MCP runtime",e);}}
    private String trim(String value){return value==null?"":value.trim();}
    private void revision(Long actual,Long expected){if(!Objects.equals(actual,expected)) throw conflict("配置已变更，请刷新后重试");}
    private McpConfigurationPO required(McpConfigurationPO value){if(value==null) throw missing();return value;}
    private BusinessException conflict(String message){return new BusinessException(ErrorCode.CONFLICT,message);}
    private BusinessException missing(){return new BusinessException(ErrorCode.NOT_FOUND,"MCP 配置不存在");}
}
