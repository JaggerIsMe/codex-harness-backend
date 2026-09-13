package com.myharness.codex.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.myharness.codex.entity.dto.WorkflowDTO.*;
import com.myharness.codex.entity.vo.OrchestrationStepResultVO;
import com.myharness.codex.exception.BusinessException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Explicit data binding and result checks, independent of scheduling and model permissions. */
public final class WorkflowDataContract {
    private static final Pattern VARIABLE=Pattern.compile("\\{\\{(goal|result:([a-zA-Z0-9_-]+)|inputs|input:([a-zA-Z][a-zA-Z0-9_]{0,63})|file:([a-zA-Z][a-zA-Z0-9_]{0,63})|outputFile:([a-zA-Z][a-zA-Z0-9_]{0,63})|outputSchema)}}");
    private final ObjectMapper json;
    public WorkflowDataContract(ObjectMapper json){this.json=json;}
    public static <T> List<T> list(List<T> value){return value==null?List.of():value;}
    public static boolean configured(Node n) {
        return !list(n.inputs()).isEmpty() || schema(n) || !list(n.inputFiles()).isEmpty() || !list(n.outputFiles()).isEmpty();
    }
    private static boolean schema(Node n){return n.outputSchema()!=null && !n.outputSchema().isNull();}
    public void validate(Node n,Function<String,Node> upstream) {
        require("EXPERT".equals(n.kind()) || !configured(n),"只有 Expert 节点可配置输入输出");
        if(!"EXPERT".equals(n.kind()))return;
        WorkflowOutputSchema.definition(n.outputSchema());
        Set<String> files=files(n.inputFiles(),true,upstream),outputs=files(n.outputFiles(),false,upstream),inputs=new HashSet<>();
        require(list(n.inputs()).size()<=40,"输入字段最多 40 个");
        for(var input:list(n.inputs())) {
            require(input!=null,"输入字段缺失");name(input.name(),inputs);
            require(input.source()!=null,"输入来源缺失");
            switch(input.source()) {
                case "GOAL" -> { }
                case "CONSTANT" -> require(input.value()!=null && input.value().length()<=12000,"固定输入最多 12000 字符");
                case "RESULT_TEXT" -> upstream.apply(input.sourceNodeId());
                case "RESULT_JSON" -> {upstream.apply(input.sourceNodeId());pointer(input.pointer());}
                case "FILE" -> require(files.contains(input.fileName()),"输入引用的文件别名不存在");
                default -> throw new IllegalArgumentException("输入来源不受支持");
            }
        }
        var matcher=VARIABLE.matcher(n.objective());
        while(matcher.find()) {
            if(matcher.group(2)!=null)upstream.apply(matcher.group(2));
            if(matcher.group(3)!=null)require(inputs.contains(matcher.group(3)),"职责引用了未配置的输入字段");
            if(matcher.group(4)!=null)require(files.contains(matcher.group(4)),"职责引用了未配置的输入文件");
            if(matcher.group(5)!=null)require(outputs.contains(matcher.group(5)),"职责引用了未配置的输出文件");
            if("outputSchema".equals(matcher.group(1)))require(schema(n),"职责引用了未配置的输出 Schema");
        }
    }
    private Set<String> files(List<FileReference> values,boolean input,Function<String,Node> upstream) {
        require(list(values).size()<=20,"输入、输出文件各最多 20 个");Set<String> names=new HashSet<>();
        for(var file:list(values)) {
            require(file!=null,"文件引用缺失");name(file.name(),names);
            if(file.sourceNodeId()!=null && !file.sourceNodeId().isEmpty()) {
                require(input && (file.path()==null || file.path().isEmpty()),"输出文件需直接指定相对路径，输入文件不能同时指定两种来源");
                var source=upstream.apply(file.sourceNodeId());
                require(list(source.outputFiles()).stream().anyMatch(f->f!=null && Objects.equals(f.name(),file.sourceFile())),"上游未声明该输出文件");
            } else {
                require(file.sourceFile()==null || file.sourceFile().isEmpty(),"上游文件引用缺少来源节点");path(file.path());
            }
        }
        return names;
    }
    private static void name(String name,Set<String> names){require(name!=null && name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") && names.add(name),"字段或文件别名需以字母开头，使用字母数字下划线，且不重复（最多 64 字符）");}
    private static void path(String path) {
        try{WorkspaceFileService.validateReferencePath(path);}catch(BusinessException failure){throw new IllegalArgumentException(failure.getMessage());}
    }
    private static void pointer(String pointer) {
        require(pointer!=null && pointer.length()<=500 && (pointer.isEmpty() || pointer.startsWith("/")) && !Pattern.compile("~(?![01])").matcher(pointer).find(),"输入 JSON Pointer 无效");
    }
    public String message(Node n,String goal,Function<String,OrchestrationStepResultVO> results) {
        var filePaths=new LinkedHashMap<String,String>();
        for(var file:list(n.inputFiles())) {
            String value=file.path();
            if(file.sourceNodeId()!=null && !file.sourceNodeId().isEmpty()) {
                value=list(results.apply(file.sourceNodeId()).files()).stream().filter(f->Objects.equals(f.name(),file.sourceFile())).map(FileReference::path).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("上游交接结果缺少输出文件引用："+file.name()));
            }
            path(value);filePaths.put(file.name(),value);
        }
        ObjectNode inputs=json.createObjectNode();
        for(var input:list(n.inputs())) {
            JsonNode value=switch(input.source()) {
                case "GOAL" -> TextNode.valueOf(goal);
                case "CONSTANT" -> TextNode.valueOf(input.value());
                case "FILE" -> TextNode.valueOf(filePaths.get(input.fileName()));
                case "RESULT_TEXT" -> TextNode.valueOf(results.apply(input.sourceNodeId()).summary());
                case "RESULT_JSON" -> parse(results.apply(input.sourceNodeId()).summary(),"输入来源").at(input.pointer());
                default -> throw new IllegalArgumentException("输入来源不受支持");
            };
            if(value==null || value.isMissingNode()) {
                require(!input.required(),"必填输入缺失："+input.name());value=NullNode.instance;
            }
            inputs.set(input.name(),value);
        }
        var matcher=VARIABLE.matcher(n.objective());StringBuilder out=new StringBuilder();
        while(matcher.find()) {
            String key=matcher.group(1),replacement;
            if("goal".equals(key))replacement=goal;
            else if("inputs".equals(key))replacement=inputs.toString();
            else if("outputSchema".equals(key))replacement=n.outputSchema().toString();
            else if(matcher.group(2)!=null)replacement=results.apply(matcher.group(2)).summary();
            else if(matcher.group(3)!=null){var value=inputs.get(matcher.group(3));replacement=value.isTextual()?value.textValue():value.toString();}
            else if(matcher.group(4)!=null)replacement=filePaths.get(matcher.group(4));
            else replacement=list(n.outputFiles()).stream().filter(f->f.name().equals(matcher.group(5))).findFirst().orElseThrow().path();
            matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(replacement));
            require(out.length()<=100000,"节点消息超过 100000 字符");
        }
        matcher.appendTail(out);require(out.length()<=100000,"节点消息超过 100000 字符");return out.toString();
    }
    public OrchestrationStepResultVO output(Node n,OrchestrationStepResultVO result) {
        JsonNode value=null;
        if(schema(n)){value=parse(result.summary(),"最终输出");WorkflowOutputSchema.validate(n.outputSchema(),value);}
        return new OrchestrationStepResultVO(configured(n)?2:result.schemaVersion(),result.summary(),result.sourceMessageIds(),
            result.sourceTurnId(),result.expertVersionId(),result.truncated(),value,List.copyOf(list(n.outputFiles())));
    }
    private JsonNode parse(String text,String label) {
        try {
            var value=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(text);
            require(value!=null,label+"不是有效 JSON");return value;
        }catch(Exception failure){throw new IllegalArgumentException(label+"不是严格有效 JSON，请移除说明文字、代码围栏或重复字段");}
    }
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
}
