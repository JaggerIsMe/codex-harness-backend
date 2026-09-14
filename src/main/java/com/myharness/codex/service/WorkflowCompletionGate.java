package com.myharness.codex.service;

import com.fasterxml.jackson.databind.*;
import com.myharness.codex.entity.dto.WorkflowDTO;
import com.myharness.codex.entity.vo.OrchestrationStepResultVO;
import java.util.*;

/** Fail closed on missing control evidence; prose is never a completion signal. */
public final class WorkflowCompletionGate {
    private WorkflowCompletionGate() {}
    public static JsonNode command(ObjectMapper json,String definition,com.myharness.codex.entity.po.OrchestrationStepPO step) {
        return command(json,definition,step,List.of());
    }
    public static JsonNode command(ObjectMapper json,String definition,com.myharness.codex.entity.po.OrchestrationStepPO step,
        List<com.myharness.codex.entity.po.OrchestrationStepPO> steps) {
        try {
            var workflow=json.readValue(definition,WorkflowDTO.class);
            var node=workflow.nodes().get(step.getPosition());
            var previous=read(json,step.getCheckpointJson());
            var command=json.createObjectNode().put("protocol",1);
            var outputs=command.putArray("outputs");
            var paths=new HashSet<String>();
            for(var file:WorkflowDataContract.list(node.outputFiles())) {
                if(!paths.add(file.path()))continue;
                var item=outputs.addObject().put("path",file.path());
                if(step.getTurnId()!=null) {
                    String baseline="UNVERIFIED";
                    for(var evidence:previous.path("files"))if(file.path().equals(evidence.path("path").asText()))baseline=evidence.path("before").asText("UNVERIFIED");
                    item.put("before",baseline);
                }
            }
            var inputs=command.putArray("inputs");
            for(var file:WorkflowDataContract.list(node.inputFiles())) {
                String path=file.path(),expected=null;
                if(file.sourceNodeId()!=null && !file.sourceNodeId().isEmpty()) {
                    var source=workflow.nodes().stream().filter(n->n.id().equals(file.sourceNodeId())).findFirst().orElseThrow();
                    path=WorkflowDataContract.list(source.outputFiles()).stream().filter(f->f.name().equals(file.sourceFile())).findFirst().orElseThrow().path();
                    int position=workflow.nodes().indexOf(source);
                    var sourceStep=steps.stream().filter(s->s.getPosition()==position).findFirst().orElseThrow();
                    expected="UNVERIFIED";
                    for(var proof:read(json,sourceStep.getCheckpointJson()).path("files"))if(path.equals(proof.path("path").asText()))expected=proof.path("after").asText("UNVERIFIED");
                }
                var input=inputs.addObject().put("path",path);
                if(expected!=null)input.put("expected",expected);
            }
            return command;
        }catch(Exception failure){throw new IllegalArgumentException("节点执行协议配置无效",failure);}
    }
    public static JsonNode read(ObjectMapper json,String value) {
        try {return value==null?json.createObjectNode():json.readTree(value);}catch(Exception failure){return json.createObjectNode();}
    }
    public static OrchestrationStepResultVO validate(WorkflowDTO.Node node,JsonNode receipt,OrchestrationStepResultVO text) {
        if(receipt.path("protocol").asInt()!=1 || !"COMPLETE".equals(receipt.path("state").asText())
            || !receipt.path("summary").isTextual() || receipt.path("summary").asText().isBlank())
            throw new IllegalArgumentException("缺少有效的节点完成回执，请核实节点执行状态");
        if(receipt.path("unresolvedApproval").asBoolean(true))throw new IllegalArgumentException("本轮结束时仍有未处理的问题或审批，请核实相关决定");
        var evidence=new HashMap<String,JsonNode>();
        for(var file:receipt.path("files")) {
            if(evidence.put(file.path("path").asText(),file)!=null)throw new IllegalArgumentException("输出文件检查记录重复");
        }
        for(var file:WorkflowDataContract.list(node.outputFiles())) {
            var proof=evidence.get(file.path());
            if(proof==null)throw new IllegalArgumentException("缺少输出文件检查回执："+file.path());
            String before=proof.path("before").asText(),after=proof.path("after").asText();
            if(!(before.equals("MISSING")||before.equals("EMPTY")||before.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("节点开始前的文件基线无法核实，请复制到画布重新执行："+file.path());
            if(!after.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("输出文件缺失、为空、超限或无法安全核实："+file.path());
            if(before.equals(after))throw new IllegalArgumentException("输出文件与节点开始前相同，不能作为本次产出："+file.path());
        }
        String summary=receipt.path("summary").asText();
        if(summary.length()>16000)throw new IllegalArgumentException("节点结果超过 16000 字符");
        return new OrchestrationStepResultVO(3,summary,text.sourceMessageIds(),text.sourceTurnId(),text.expertVersionId(),false);
    }
}
