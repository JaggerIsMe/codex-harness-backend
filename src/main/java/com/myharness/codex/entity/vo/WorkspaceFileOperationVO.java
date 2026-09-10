package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.po.WorkspaceFileOperationPO;
public record WorkspaceFileOperationVO(String id,String kind,String path,String status,String error,
                                      String targetPath,String code,String contentState,
                                      com.myharness.codex.entity.dto.WorkspaceFileResultDTO result,long attachmentCount) {
    public WorkspaceFileOperationVO(String id,String kind,String path,String status,String error) {
        this(id,kind,path,status,error,null,null,"NONE",null,0);
    }
    public WorkspaceFileOperationVO(WorkspaceFileOperationPO p) {
        this(p.getId().toString(),p.getKind(),p.getPath(),p.getStatus(),p.getError(),p.getTargetPath(),p.getCode(),
                p.getContentState()==null ? "NONE" : p.getContentState(),parse(p.getResultJson()),p.getAttachmentCount());
    }
    private static com.myharness.codex.entity.dto.WorkspaceFileResultDTO parse(String text) {
        if(text==null) return null;
        try {return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text,com.myharness.codex.entity.dto.WorkspaceFileResultDTO.class);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Corrupt workspace result",e);}
    }
}
