package com.myharness.codex.entity.dto;

import java.util.List;

public class ModelRuntimeSpecDTO {
    private String providerName;
    private String baseUrl;
    private String modelId;
    private List<String> inputModalities=List.of("TEXT");
    private int contextWindowTokens=128000;
    public String getProviderName(){return providerName;}
    public void setProviderName(String value){providerName=value;}
    public String getBaseUrl(){return baseUrl;}
    public void setBaseUrl(String value){baseUrl=value;}
    public String getModelId(){return modelId;}
    public void setModelId(String value){modelId=value;}
    public List<String> getInputModalities(){return inputModalities==null?List.of():inputModalities;}
    public void setInputModalities(List<String> value){inputModalities=value;}
    public int getContextWindowTokens(){return contextWindowTokens;}
    public void setContextWindowTokens(int value){contextWindowTokens=value;}
}
