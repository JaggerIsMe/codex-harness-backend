package com.myharness.codex.entity.po;
public class WorkspaceFileItemPO {
    private long itemIndex;
    private String path,entryType,status,code,error;
    public long getItemIndex(){return itemIndex;} public void setItemIndex(long v){itemIndex=v;}
    public String getPath(){return path;} public void setPath(String v){path=v;}
    public String getEntryType(){return entryType;} public void setEntryType(String v){entryType=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public String getError(){return error;} public void setError(String v){error=v;}
    public com.myharness.codex.entity.dto.WorkspaceFileItemsDTO.Item toItem(){return new com.myharness.codex.entity.dto.WorkspaceFileItemsDTO.Item(path,entryType,status,code,error);}
}
