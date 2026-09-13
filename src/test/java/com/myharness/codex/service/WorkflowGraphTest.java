package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.WorkflowDTO;
import com.myharness.codex.entity.dto.WorkflowDTO.*;
import com.myharness.codex.entity.po.OrchestrationStepPO;
import com.myharness.codex.entity.vo.OrchestrationStepResultVO;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowGraphTest {
    final ObjectMapper json=new ObjectMapper();
    Node expert(String id,String next,String message){return new Node(id,"EXPERT",id,8L,message,next,null,20,20);}
    Node branch(String source,String yes,String no){return new Node("b","BRANCH","判断",null,"",null,new Condition(source,"EQUALS","","yes",yes,no),300,20);}
    WorkflowGraph graph(Node... nodes){return new WorkflowGraph(new WorkflowDTO(2,"a",List.of(nodes)),json);}
    Node start(String id,String next){return new Node(id,"START","开始",null,"",next,null,0,0);}
    @Test void entryIsUniqueConnectedAndCannotBeBypassedOrReceiveIncomingEdges() {
        var task=expert("a",null,"仅用户职责");
        assertDoesNotThrow(()->new WorkflowGraph(new WorkflowDTO(4,"s",List.of(start("s","a"),task)),json));
        for(var definition:List.of(
            new WorkflowDTO(4,"a",List.of(task)),
            new WorkflowDTO(4,"a",List.of(start("s","a"),task)),
            new WorkflowDTO(4,"s",List.of(start("s",null),task)),
            new WorkflowDTO(4,"s",List.of(start("s","a"),start("s2","a"),task)),
            new WorkflowDTO(4,"s",List.of(start("s","a"),expert("a","s","循环"))),
            new WorkflowDTO(3,"s",List.of(start("s","a"),task))))
            assertThrows(IllegalArgumentException.class,()->new WorkflowGraph(definition,json));
    }
    @Test void persistedEntryAdvancesToExpertWithoutChangingItsMessage() throws Exception {
        var g=new WorkflowGraph(new WorkflowDTO(4,"s",List.of(start("s","a"),expert("a",null,"  用户职责 {{goal}}  "))),json);
        var s=steps(2);assertEquals(0,g.current(s).getPosition());complete(s.getFirst(),"流程开始");
        assertEquals(1,g.current(s).getPosition());
        assertEquals("  用户职责 目标  ",g.message(g.definition().nodes().get(1),"目标",s));
        complete(s.get(1),"完成");assertNull(g.current(s));
    }
    List<OrchestrationStepPO> steps(int size) {
        List<OrchestrationStepPO> list=new ArrayList<>();for(int i=0;i<size;i++){var s=new OrchestrationStepPO();s.setId((long)i+1);s.setPosition(i);s.setStatus("PENDING");list.add(s);}return list;
    }
    void complete(OrchestrationStepPO step,String summary) throws Exception {
        step.setStatus("SUCCEEDED");step.setResultJson(json.writeValueAsString(new OrchestrationStepResultVO(1,summary,List.of(99L),7L,8L,false)));
    }
    @Test void acceptsAnyNumberAndCustomExpertResponsibilities() {
        var g=graph(expert("a",null,"  用户原文\n不添加其它内容。 "));
        assertEquals("  用户原文\n不添加其它内容。 ",g.message(g.definition().nodes().getFirst(),"不会自动加入",steps(1)));
    }
    @Test void onlyExplicitReferencesAreExpandedOnce() throws Exception {
        var g=graph(expert("a","z","用户任务"),expert("z",null,"职责\n{{goal}}\n{{result:a}}"));var s=steps(2);complete(s.getFirst(),"上游保留 {{goal}} $1 \\ 原文");
        assertEquals("职责\n目标\n上游保留 {{goal}} $1 \\ 原文",g.message(g.definition().nodes().get(1),"目标",s));
    }
    @Test void selectedBranchReconvergesOnceAndDoesNotWaitForOtherPath() throws Exception {
        var g=graph(expert("a","b","检查"),branch("a","yes","no"),expert("yes","end","满足"),expert("no","end","不满足"),expert("end",null,"结束"));var s=steps(5);
        complete(s.get(0),"yes");assertEquals(1,g.current(s).getPosition());assertTrue(g.evaluate(g.definition().nodes().get(1).condition(),s));
        complete(s.get(1),"true");complete(s.get(0),"changed"); // Later data cannot revise an accepted choice.
        assertEquals(2,g.current(s).getPosition());assertFalse(g.potentialPositions(s).contains(3));assertTrue(g.potentialPositions(s).contains(4));
        s.get(3).setStatus("SKIPPED");complete(s.get(2),"done");assertEquals(4,g.current(s).getPosition());complete(s.get(4),"done");assertNull(g.current(s));
    }
    @Test void falseBranchSelectsOnlyItsTarget() throws Exception {
        var g=graph(expert("a","b","检查"),branch("a","yes","no"),expert("yes",null,"满足"),expert("no",null,"不满足"));var s=steps(4);
        complete(s.get(0),"no");assertFalse(g.evaluate(g.definition().nodes().get(1).condition(),s));complete(s.get(1),"false");assertEquals(3,g.current(s).getPosition());
    }
    @Test void refusesCyclesDisconnectedNodesAndMissingEdges() {
        assertThrows(IllegalArgumentException.class,()->graph(expert("a","b","x"),expert("b","a","y")));
        assertThrows(IllegalArgumentException.class,()->graph(expert("a",null,"x"),expert("b",null,"y")));
        assertThrows(IllegalArgumentException.class,()->graph(expert("a","missing","x")));
        assertThrows(IllegalArgumentException.class,()->graph(expert("a",null,"x"),expert("a",null,"y")));
    }
    @Test void rejectsReferencesThatAreNotAvailableOnEveryIncomingPath() {
        assertThrows(IllegalArgumentException.class,()->graph(expert("a","b","x"),branch("a","yes","no"),expert("yes","end","y"),expert("no","end","n"),expert("end",null,"{{result:yes}}")));
        assertThrows(IllegalArgumentException.class,()->graph(expert("a","b","x"),branch("yes","yes","no"),expert("yes",null,"y"),expert("no",null,"n")));
        assertThrows(IllegalArgumentException.class,()->graph(expert("a",null,"{{result:a}}")));
    }
    @Test void jsonConditionsFailOnMissingInvalidOrNonScalarData() throws Exception {
        var g=graph(expert("a","b","x"),branch("a","yes","no"),expert("yes",null,"y"),expert("no",null,"n"));var s=steps(4);
        var c=new Condition("a","JSON_EQUALS","/approved","true","yes","no");
        complete(s.get(0),"{\"approved\":true}");assertTrue(g.evaluate(c,s));
        complete(s.get(0),"{\"approved\":false}");assertFalse(g.evaluate(c,s));
        for(String invalid:List.of("{}","not JSON","{\"approved\":[]}","{\"approved\":true} false")) {
            complete(s.get(0),invalid);assertThrows(IllegalArgumentException.class,()->g.evaluate(c,s));
        }
    }
    @Test void conditionComparisonDoesNotTrimOrRunScripts() throws Exception {
        var g=graph(expert("a",null,"x"));var s=steps(1);complete(s.getFirst(),"yes\n");
        assertFalse(g.evaluate(new Condition("a","EQUALS","","yes",null,null),s));
        assertTrue(g.evaluate(new Condition("a","CONTAINS","","yes",null,null),s));
    }
    @Test void refusesUnsupportedVersionAndIncompleteBranch() {
        assertThrows(IllegalArgumentException.class,()->new WorkflowGraph(new WorkflowDTO(1,"a",List.of(expert("a",null,"x"))),json));
        assertThrows(IllegalArgumentException.class,()->graph(expert("a","b","x"),branch("a","yes","yes"),expert("yes",null,"y")));
    }
    @Test void rejectsOversizedExpansionWithoutTruncatingUserContent() throws Exception {
        var g=graph(expert("a","z","x"),expert("z",null,"{{result:a}}".repeat(10)));var s=steps(2);complete(s.getFirst(),"x".repeat(16000));
        assertThrows(IllegalArgumentException.class,()->g.message(g.definition().nodes().get(1),"goal",s));
    }
    @Test void branchMayEndWithoutSchedulingAnotherExpert() throws Exception {
        var g=graph(expert("a","b","检查"),branch("a","yes","end"),expert("yes","end","执行"),new Node("end","END","结束",null,"",null,null,900,0));var s=steps(4);
        complete(s.get(0),"no");complete(s.get(1),"false");assertEquals(3,g.current(s).getPosition());assertFalse(g.potentialPositions(s).contains(2));
        complete(s.get(3),"流程结束");assertNull(g.current(s));
    }
}
