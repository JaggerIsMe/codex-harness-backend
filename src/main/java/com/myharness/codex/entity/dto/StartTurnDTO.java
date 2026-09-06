package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class StartTurnDTO {
    @Size(max=100000) private String message;
    @Size(max=64) @jakarta.validation.constraints.Pattern(regexp="[a-zA-Z0-9-]{1,64}") private String clientRequestId;
    @Size(max=100) private java.util.List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long> attachmentIds=java.util.List.of();
    public String getClientRequestId(){return clientRequestId;}
    public void setClientRequestId(String value){clientRequestId=value;}
    public java.util.List<Long> getAttachmentIds(){return attachmentIds==null ? java.util.List.of() : attachmentIds;}
    public void setAttachmentIds(java.util.List<Long> value){attachmentIds=value;}
    public String getMessage() { return message; }
    public void setMessage(String value) { message=value; }
}
