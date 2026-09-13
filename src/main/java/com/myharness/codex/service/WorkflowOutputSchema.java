package com.myharness.codex.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Bounded JSON Schema subset. Unknown constraints are rejected rather than silently ignored. */
public final class WorkflowOutputSchema {
    private static final Set<String> TYPES=Set.of("object","array","string","number","integer","boolean","null");
    private static final Set<String> KEYS=Set.of("type","description","properties","required","additionalProperties","items","enum");
    private WorkflowOutputSchema() {}
    public static void definition(JsonNode schema) {
        if(schema==null || schema.isNull())return;
        require(schema.toString().length()<=16000,"输出 Schema 超过 16000 字符");
        definition(schema,0,new int[]{0},"$");
    }
    private static void definition(JsonNode s,int depth,int[] count,String path) {
        require(s.isObject() && depth<=8 && ++count[0]<=128,path+"：Schema 需为对象，最多 8 层、128 项");
        s.fieldNames().forEachRemaining(k->require(KEYS.contains(k),path+"：不支持 Schema 关键字 "+k));
        String type=s.path("type").asText();require(TYPES.contains(type),path+"：请选择单一输出类型");
        if(s.has("description"))require(s.get("description").isTextual() && s.get("description").textValue().length()<=1000,path+"：字段说明无效");
        if(s.has("properties")) {
            require("object".equals(type) && s.get("properties").isObject(),path+"：properties 只用于对象");
            s.get("properties").fields().forEachRemaining(e->{
                require(!e.getKey().isEmpty() && e.getKey().length()<=100,path+"：字段名无效");
                definition(e.getValue(),depth+1,count,path+"/"+e.getKey());
            });
        }
        if(s.has("required")) {
            require("object".equals(type) && s.get("required").isArray(),path+"：required 必须为数组");
            Set<String> names=new HashSet<>();
            for(var key:s.get("required"))require(key.isTextual() && s.path("properties").has(key.textValue()) && names.add(key.textValue()),path+"：必填字段未定义或重复");
        }
        if(s.has("additionalProperties"))require("object".equals(type) && s.get("additionalProperties").isBoolean(),path+"：additionalProperties 仅支持布尔值");
        if("array".equals(type))require(s.has("items"),path+"：数组需配置 items");
        if(s.has("items")) {
            require("array".equals(type),path+"：items 只用于数组");definition(s.get("items"),depth+1,count,path+"/items");
        }
        if(s.has("enum")) {
            require(s.get("enum").isArray() && !s.get("enum").isEmpty() && s.get("enum").size()<=100,path+"：enum 需包含 1–100 个标量");
            for(var item:s.get("enum"))require(item.isValueNode(),path+"：enum 仅支持标量");
        }
    }
    public static void validate(JsonNode schema,JsonNode output) {
        definition(schema);
        if(schema!=null && !schema.isNull())validate(schema,output,"$");
    }
    private static void validate(JsonNode s,JsonNode v,String path) {
        String type=s.path("type").asText();
        boolean matches=v!=null && switch(type) {
            case "object"->v.isObject();case "array"->v.isArray();case "string"->v.isTextual();
            case "number"->v.isNumber();case "integer"->v.isNumber() && v.decimalValue().stripTrailingZeros().scale()<=0;
            case "boolean"->v.isBoolean();case "null"->v.isNull();default->false;
        };
        require(matches,"输出 Schema 校验失败："+path+" 应为 "+type);
        if(s.has("enum")) {
            boolean found=false;for(var candidate:s.get("enum"))if(equal(candidate,v)){found=true;break;}
            require(found,"输出 Schema 校验失败："+path+" 不在允许值中");
        }
        if(v.isObject()) {
            for(var key:s.path("required"))require(v.has(key.textValue()),"输出 Schema 校验失败：缺少 "+path+"/"+key.textValue());
            v.fields().forEachRemaining(e->{
                var child=s.path("properties").get(e.getKey());
                if(child!=null)validate(child,e.getValue(),path+"/"+e.getKey());
                else require(s.path("additionalProperties").asBoolean(true),"输出 Schema 校验失败：不允许字段 "+path+"/"+e.getKey());
            });
        }
        if(v.isArray())for(int i=0;i<v.size();i++)validate(s.get("items"),v.get(i),path+"/"+i);
    }
    private static boolean equal(JsonNode a,JsonNode b){return a.isNumber() && b.isNumber()?a.decimalValue().compareTo(b.decimalValue())==0:a.equals(b);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
}
