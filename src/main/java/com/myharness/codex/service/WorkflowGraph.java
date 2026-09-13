package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.codex.entity.dto.WorkflowDTO;
import com.myharness.codex.entity.dto.WorkflowDTO.Node;
import com.myharness.codex.entity.dto.WorkflowDTO.Condition;
import com.myharness.codex.entity.po.OrchestrationStepPO;
import com.myharness.codex.entity.vo.OrchestrationStepResultVO;
import java.util.*;
import java.util.regex.Pattern;

/** Validates graph invariants and evaluates a single durable path without side effects. */
public final class WorkflowGraph {
    private static final Pattern REFERENCE=Pattern.compile("\\{\\{(goal|result:([a-zA-Z0-9_-]+))}}");
    private final WorkflowDTO definition;
    private final Map<String,Node> nodes=new LinkedHashMap<>();
    private final Map<String,Integer> positions=new HashMap<>();
    private final ObjectMapper json;
    public WorkflowGraph(WorkflowDTO definition,ObjectMapper json) {
        this.definition=definition;this.json=json;
        require(definition!=null && Set.of(2,3,4).contains(definition.schemaVersion()),"工作流版本不受支持");
        require(definition.nodes()!=null && !definition.nodes().isEmpty() && definition.nodes().size()<=(definition.schemaVersion()==4?41:40),"工作流最多包含 40 个流程节点（不含开始节点）");
        for(var n:definition.nodes()) {
            require(n!=null && n.id()!=null && n.id().matches("[a-zA-Z0-9_-]{1,64}"),"节点 ID 无效");
            require(nodes.putIfAbsent(n.id(),n)==null,"节点 ID 重复");positions.put(n.id(),positions.size());
            require(n.name()!=null && !n.name().isBlank() && n.name().length()<=80,"节点名称需为 1–80 字符");
            require(Double.isFinite(n.x()) && Double.isFinite(n.y()) && n.x()>=0 && n.y()>=0 && n.x()<=10000 && n.y()<=10000,"节点坐标无效");
            require(definition.schemaVersion()>=3 || !WorkflowDataContract.configured(n),"输入输出配置需要工作流版本 3");
            if("EXPERT".equals(n.kind())) {
                require(n.expertId()!=null && n.expertId()>0,"Expert 节点需选择专家");
                require(n.objective()!=null && !n.objective().isBlank() && n.objective().length()<=12000,"节点职责需为 1–12000 字符");
                require(n.condition()==null,"Expert 节点不能包含条件");
            } else if("START".equals(n.kind())) {
                require(definition.schemaVersion()==4,"开始节点需要工作流版本 4");
                require(n.expertId()==null && n.condition()==null && (n.objective()==null || n.objective().isEmpty()),"开始节点不能配置执行内容");
                require(n.next()!=null && !n.next().isBlank(),"开始节点需连接下一步");
            } else if("END".equals(n.kind())) {
                require(n.expertId()==null && n.next()==null && n.condition()==null && (n.objective()==null || n.objective().isEmpty()),"结束节点不能配置执行内容或出口");
            } else {
                require("BRANCH".equals(n.kind()),"节点类型不受支持");
                require(n.expertId()==null && n.next()==null && (n.objective()==null || n.objective().isEmpty()),"条件节点不执行 Expert 消息");
                var c=n.condition();require(c!=null,"条件节点需配置条件");
                require(c.operator()!=null && Set.of("EQUALS","CONTAINS","JSON_EQUALS").contains(c.operator()),"条件操作不受支持");
                require(c.value()!=null && c.value().length()<=4000,"条件值过长或缺失");
                require(c.whenTrue()!=null && c.whenFalse()!=null && !c.whenTrue().equals(c.whenFalse()),"条件需连接两个不同的出口节点");
                if("JSON_EQUALS".equals(c.operator())) {
                    require(c.pointer()!=null && c.pointer().length()<=500 && (c.pointer().isEmpty() || c.pointer().startsWith("/")),"JSON Pointer 无效");
                    scalar(c.value());
                }
            }
        }
        require(nodes.values().stream().anyMatch(n->"EXPERT".equals(n.kind())),"至少需要一个 Expert 节点");
        require(nodes.containsKey(definition.startNodeId()),"请选择开始节点");
        if(definition.schemaVersion()==4) {
            require(nodes.values().stream().filter(n->"START".equals(n.kind())).count()==1,"工作流必须包含唯一的开始节点");
            require("START".equals(nodes.get(definition.startNodeId()).kind()),"工作流必须从开始节点进入");
        }
        Map<String,List<String>> parents=new HashMap<>();nodes.keySet().forEach(k->parents.put(k,new ArrayList<>()));
        for(var n:nodes.values())for(String target:targets(n)) {
            require(nodes.containsKey(target),"连线引用不存在的节点");parents.get(target).add(n.id());
        }
        require(parents.get(definition.startNodeId()).isEmpty(),"开始节点不能有入线");
        Map<String,Integer> degree=new HashMap<>();parents.forEach((k,v)->degree.put(k,v.size()));
        ArrayDeque<String> ready=new ArrayDeque<>();ready.add(definition.startNodeId());
        Map<String,Set<String>> dominators=new HashMap<>();
        while(!ready.isEmpty()) {
            String id=ready.remove();var incoming=parents.get(id);Set<String> prior=new HashSet<>();
            if(!incoming.isEmpty()) {
                prior.addAll(dominators.get(incoming.get(0)));
                for(String parent:incoming)prior.retainAll(dominators.get(parent));
            }
            var n=nodes.get(id);
            if(definition.schemaVersion()>=3)new WorkflowDataContract(json).validate(n,source->{requireSource(source,prior);return nodes.get(source);});
            if("BRANCH".equals(n.kind()))requireSource(n.condition().sourceNodeId(),prior);
            else if("EXPERT".equals(n.kind())) {
                var matcher=REFERENCE.matcher(n.objective());
                while(matcher.find())if(matcher.group(2)!=null)requireSource(matcher.group(2),prior);
            }
            prior.add(id);dominators.put(id,prior);
            for(String target:targets(n))if(degree.compute(target,(k,v)->v-1)==0)ready.add(target);
        }
        require(dominators.size()==nodes.size(),"工作流存在环或无法从开始节点到达的节点");
    }
    private void requireSource(String id,Set<String> prior) {
        require(id!=null && prior.contains(id) && "EXPERT".equals(nodes.get(id).kind()),"结果来源必须是所有到达路径上均已执行的上游 Expert");
    }
    private List<String> targets(Node n) {
        return "BRANCH".equals(n.kind())?List.of(n.condition().whenTrue(),n.condition().whenFalse()):n.next()==null?List.of():List.of(n.next());
    }
    public WorkflowDTO definition(){return definition;}
    public Node node(OrchestrationStepPO step){return definition.nodes().get(step.getPosition());}
    private OrchestrationStepPO step(String id,List<OrchestrationStepPO> steps) {
        return steps.stream().filter(s->s.getPosition()==positions.get(id)).findFirst().orElseThrow(()->new IllegalArgumentException("工作流节点记录缺失"));
    }
    /** Replays stored choices only. It never re-evaluates a completed branch. */
    public OrchestrationStepPO current(List<OrchestrationStepPO> steps) {
        String id=definition.startNodeId();
        while(id!=null) {
            var s=step(id,steps);if(!"SUCCEEDED".equals(s.getStatus()))return s;
            var n=nodes.get(id);
            if("BRANCH".equals(n.kind())) {
                String choice=result(s).summary();require("true".equals(choice)||"false".equals(choice),"已保存的分支选择无效");
                id="true".equals(choice)?n.condition().whenTrue():n.condition().whenFalse();
            } else id=n.next();
        }
        return null;
    }
    public Set<Integer> potentialPositions(List<OrchestrationStepPO> steps) {
        Set<Integer> found=new HashSet<>();ArrayDeque<String> queue=new ArrayDeque<>();queue.add(definition.startNodeId());
        while(!queue.isEmpty()) {
            String id=queue.remove();if(!found.add(positions.get(id)))continue;
            var n=nodes.get(id);var s=step(id,steps);
            if("BRANCH".equals(n.kind()) && "SUCCEEDED".equals(s.getStatus()))queue.add("true".equals(result(s).summary())?n.condition().whenTrue():n.condition().whenFalse());
            else queue.addAll(targets(n));
        }
        return found;
    }
    public String message(Node n,String goal,List<OrchestrationStepPO> steps) {
        if(definition.schemaVersion()>=3)return new WorkflowDataContract(json).message(n,goal,id->result(step(id,steps)));
        var matcher=REFERENCE.matcher(n.objective());StringBuilder out=new StringBuilder();
        while(matcher.find())matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(matcher.group(2)==null?goal:result(step(matcher.group(2),steps)).summary()));
        matcher.appendTail(out);require(out.length()<=100000,"节点消息超过 100000 字符");return out.toString();
    }
    public OrchestrationStepResultVO output(Node node,OrchestrationStepResultVO result) {
        return new WorkflowDataContract(json).output(node,result);
    }
    public boolean evaluate(Condition condition,List<OrchestrationStepPO> steps) {
        String text=result(step(condition.sourceNodeId(),steps)).summary();
        return switch(condition.operator()) {
            case "EQUALS" -> text.equals(condition.value());
            case "CONTAINS" -> text.contains(condition.value());
            case "JSON_EQUALS" -> {
                JsonNode value;
                try {value=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);require(value!=null,"上游未返回 JSON");value=value.at(condition.pointer());}
                catch(Exception failure){throw new IllegalArgumentException("条件来源不是有效 JSON 或 Pointer 无效");}
                require(!value.isMissingNode() && value.isValueNode(),"条件 JSON 字段缺失或不是标量");
                yield value.equals(scalar(condition.value()));
            }
            default -> throw new IllegalArgumentException("条件操作不受支持");
        };
    }
    private JsonNode scalar(String text) {
        try {JsonNode value=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);require(value!=null && value.isValueNode(),"条件值必须是 JSON 标量");return value;}
        catch(Exception failure){throw new IllegalArgumentException("条件值必须是 JSON 标量");}
    }
    private OrchestrationStepResultVO result(OrchestrationStepPO s) {
        require("SUCCEEDED".equals(s.getStatus()) && s.getResultJson()!=null,"上游结果尚未就绪");
        try{return json.readValue(s.getResultJson(),OrchestrationStepResultVO.class);}
        catch(Exception failure){throw new IllegalArgumentException("上游结果无效");}
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
