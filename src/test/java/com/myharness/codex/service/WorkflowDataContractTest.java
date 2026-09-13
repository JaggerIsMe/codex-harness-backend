package com.myharness.codex.service;

import com.fasterxml.jackson.databind.*;
import com.myharness.codex.entity.dto.WorkflowDTO;
import com.myharness.codex.entity.dto.WorkflowDTO.*;
import com.myharness.codex.entity.po.OrchestrationStepPO;
import com.myharness.codex.entity.vo.OrchestrationStepResultVO;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowDataContractTest {
    final ObjectMapper json=new ObjectMapper();
    final WorkflowDataContract data=new WorkflowDataContract(json);
    Node node(String id,String message,List<Input> inputs,JsonNode schema,List<FileReference> in,List<FileReference> out) {
        return new Node(id,"EXPERT",id,8L,message,null,null,0,0,inputs,schema,in,out);
    }
    OrchestrationStepResultVO result(String text){return new OrchestrationStepResultVO(1,text,List.of(1L),2L,3L,false);}
    Input field(String name,String pointer,boolean required){return new Input(name,"RESULT_JSON","a",pointer,null,null,required);}
    Node source(){return node("a","task",null,null,null,List.of(new FileReference("report","docs/report.md",null,null)));}
    @Test void preservesTypedFieldsAndExpandsOnlyExplicitVariablesOnce() throws Exception {
        var n=node("b","{{inputs}}\n{{input:title}}",List.of(field("approved","/approved",true),field("title","/title",true),field("items","/items",true)),null,null,null);
        data.validate(n,id->source());
        var message=data.message(n,"not injected",id->result("{\"approved\":true,\"title\":\"{{goal}} $1\",\"items\":[1,2]}"));
        var value=json.readTree(message.substring(0,message.indexOf('\n')));
        assertTrue(value.get("approved").isBoolean());assertTrue(value.get("items").isArray());assertTrue(message.endsWith("{{goal}} $1"));assertFalse(message.contains("not injected"));
    }
    @Test void requiredMissingFieldFailsButOptionalMissingIsNullAndNullIsPresent() {
        var required=node("b","{{input:v}}",List.of(field("v","/missing",true)),null,null,null);
        assertThrows(IllegalArgumentException.class,()->data.message(required,"goal",id->result("{}")));
        assertEquals("null",data.message(required,"goal",id->result("{\"missing\":null}")));
        var optional=node("b","{{input:v}}",List.of(field("v","/missing",false)),null,null,null);
        assertEquals("null",data.message(optional,"goal",id->result("{}")));
        for(String text:List.of("{} true","```json\n{}\n```","{\"a\":1,\"a\":2}"))assertThrows(IllegalArgumentException.class,()->data.message(optional,"goal",id->result(text)));
    }
    @Test void outputSchemaValidatesTypesRequiredFieldsArraysAndUnexpectedFields() throws Exception {
        var schema=json.readTree("""
            {"type":"object","properties":{"approved":{"type":"boolean"},"items":{"type":"array","items":{"type":"integer"}}},"required":["approved"],"additionalProperties":false}
            """);
        var n=node("a","{{outputSchema}}",null,schema,null,null);data.validate(n,id->source());
        assertEquals(schema.toString(),data.message(n,"goal",id->result("")));
        assertTrue(data.output(n,result("{\"approved\":true,\"items\":[1,2.0]}")).output().get("approved").booleanValue());
        for(String text:List.of("{}","{\"approved\":\"true\"}","{\"approved\":true,\"extra\":1}","{\"approved\":true,\"items\":[1.5]}"))assertThrows(IllegalArgumentException.class,()->data.output(n,result(text)));
    }
    @Test void unsupportedSchemaConstraintsAndOversizedSchemasCannotBeIgnored() throws Exception {
        for(String schema:List.of("{\"type\":\"string\",\"pattern\":\"x\"}","{\"type\":\"object\",\"$ref\":\"https://example.com\"}","{\"type\":\"object\",\"required\":[\"missing\"]}","{\"type\":\"array\"}")) {
            var value=json.readTree(schema);assertThrows(IllegalArgumentException.class,()->WorkflowOutputSchema.definition(value));
        }
        var nested=json.createObjectNode().put("type","string");for(int i=0;i<10;i++)nested=json.createObjectNode().put("type","array").set("items",nested);
        var tooDeep=nested;assertThrows(IllegalArgumentException.class,()->WorkflowOutputSchema.definition(tooDeep));
    }
    @Test void literalGoalAndFileInputsAreNotAutomaticallyAppended() {
        var n=node("a","exact user task",List.of(new Input("goal","GOAL",null,null,null,null,true),new Input("fixed","CONSTANT",null,null,"constant",null,true)),null,List.of(new FileReference("input","docs/input.md",null,null)),null);
        data.validate(n,id->source());assertEquals("exact user task",data.message(n,"goal",id->result("previous")));
    }
    @Test void filesResolveOnlyDeclaredUpstreamLocationsWithoutClaimingFileExistence() {
        var saved=data.output(source(),result("done"));assertEquals("docs/report.md",saved.files().getFirst().path());
        var n=node("b","{{file:prior}}\n{{outputFile:next}}",null,null,List.of(new FileReference("prior",null,"a","report")),List.of(new FileReference("next","docs/final.md",null,null)));
        data.validate(n,id->source());assertEquals("docs/report.md\ndocs/final.md",data.message(n,"goal",id->saved));
        assertThrows(IllegalArgumentException.class,()->data.message(n,"goal",id->result("done")));
    }
    @Test void pathsCannotEscapeWorkspaceOrReferenceProtectedLocations() {
        for(String path:List.of("../secret","C:/secret","/etc/passwd","a\\b",".codex/auth.json","x/.git/config",".harness-upload-token","a/CON.txt","https://example.com/x","")) {
            var n=node("a","task",null,null,List.of(new FileReference("file",path,null,null)),null);
            assertThrows(IllegalArgumentException.class,()->data.validate(n,id->source()),path);
        }
    }
    @Test void graphRejectsNonDominatingMappingsAndFilesAndV2Contracts() {
        var first=new Node("a","EXPERT","a",8L,"task","c",null,0,0);
        var branch=new Node("c","BRANCH","branch",null,"",null,new Condition("a","EQUALS","","yes","yes","no"),0,0);
        var yes=new Node("yes","EXPERT","yes",8L,"task","end",null,0,0,null,null,null,List.of(new FileReference("report","report.md",null,null)));
        var no=new Node("no","EXPERT","no",8L,"task","end",null,0,0);
        var end=node("end","{{inputs}}",List.of(new Input("bad","RESULT_TEXT","yes",null,null,null,true)),null,null,null);
        assertThrows(IllegalArgumentException.class,()->new WorkflowGraph(new WorkflowDTO(3,"a",List.of(first,branch,yes,no,end)),json));
        var fileEnd=node("end","task",null,null,List.of(new FileReference("report",null,"yes","report")),null);
        assertThrows(IllegalArgumentException.class,()->new WorkflowGraph(new WorkflowDTO(3,"a",List.of(first,branch,yes,no,fileEnd)),json));
        assertThrows(IllegalArgumentException.class,()->new WorkflowGraph(new WorkflowDTO(2,"a",List.of(source())),json));
    }
    @Test void v3RoundTripPreservesBindingAndResultAndV2UserTextRemainsUnchanged() throws Exception {
        var old=node("a","{{input:unconfigured}}",null,null,null,null);
        var legacy=new WorkflowGraph(new WorkflowDTO(2,"a",List.of(old)),json);
        assertEquals(old.objective(),legacy.message(old,"goal",List.of()));
        var n=node("a","{{inputs}}",List.of(new Input("goal","GOAL",null,null,null,null,true)),null,null,null);
        var saved=json.readValue(json.writeValueAsString(new WorkflowDTO(3,"a",List.of(n))),WorkflowDTO.class);
        var graph=new WorkflowGraph(saved,json);assertEquals("{\"goal\":\"target\"}",graph.message(saved.nodes().getFirst(),"target",List.of()));
    }
}
