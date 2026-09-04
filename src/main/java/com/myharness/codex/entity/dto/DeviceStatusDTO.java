package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;

public class DeviceStatusDTO {
    @NotBlank private String status;
    public String getStatus(){return status;} public void setStatus(String value){status=value;}
}
