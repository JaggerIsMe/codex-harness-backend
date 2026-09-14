package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.WorkflowDTO;
import com.myharness.codex.entity.vo.OrchestrationStepResultVO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowCompletionGateTest {
    ObjectMapper json=new ObjectMapper();
    WorkflowDTO.Node node=new WorkflowDTO.Node("a","EXPERT","查询",4L,"用户原文",null,null,0,0,
        null,null,null,List.of(new WorkflowDTO.FileReference("report","report/data.xlsx",null,null)));
    OrchestrationStepResultVO text=new OrchestrationStepResultVO(1,"最终消息",List.of(10L),7L,8L,false);
    @Test void rejectsMissingOldUnverifiableAndUnresolvedOutputs() throws Exception {
        for(String before:List.of("MISSING","UNVERIFIED","a".repeat(64))) {
            for(String after:List.of("MISSING","UNVERIFIED","a".repeat(64))) {
                var receipt=json.createObjectNode().put("protocol",1).put("state","COMPLETE").put("summary","已生成报告").put("unresolvedApproval",false);
                receipt.putArray("files").addObject().put("path","report/data.xlsx").put("before",before).put("after",after);
                if(before.equals("MISSING") && after.matches("[a-f0-9]{64}")) {
                    assertEquals(3,WorkflowCompletionGate.validate(node,receipt,text).schemaVersion());
                    receipt.put("unresolvedApproval",true);
                }
                assertThrows(IllegalArgumentException.class,()->WorkflowCompletionGate.validate(node,receipt,text));
            }
        }
        var missing=json.readTree("{\"protocol\":1,\"state\":\"COMPLETE\",\"summary\":\"完成\",\"unresolvedApproval\":false,\"files\":[]}");
        assertThrows(IllegalArgumentException.class,()->WorkflowCompletionGate.validate(node,missing,text));
    }
    @Test void carriesOriginalBaselineInsteadOfTreatingAContinuationAsANewNode() throws Exception {
        var step=new com.myharness.codex.entity.po.OrchestrationStepPO();step.setPosition(0);step.setTurnId(7L);
        step.setCheckpointJson("{\"files\":[{\"path\":\"report/data.xlsx\",\"before\":\"MISSING\",\"after\":\""+"a".repeat(64)+"\"}]}");
        var command=WorkflowCompletionGate.command(json,json.writeValueAsString(new WorkflowDTO(3,"a",List.of(node))),step);
        assertEquals("MISSING",command.path("outputs").get(0).path("before").asText());
        step.setCheckpointJson(null);
        assertEquals("UNVERIFIED",WorkflowCompletionGate.command(json,json.writeValueAsString(new WorkflowDTO(3,"a",List.of(node))),step).path("outputs").get(0).path("before").asText());
    }
}
