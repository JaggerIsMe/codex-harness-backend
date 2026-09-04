package com.myharness.codex.entity.dto;

import javax.validation.constraints.NotBlank;

public class DeviceStatusDTO {
    @NotBlank private String status;
    public String getStatus(){return status;} public void setStatus(String value){status=value;}
}
