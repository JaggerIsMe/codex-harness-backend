package com.myharness.codex.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.codex.entity.po.ConversationMessagePO;
import java.util.List;
import java.util.Set;

/** Recovers only the current Turn's authenticated, successful tool event; never infers from prose. */
public final class WorkflowOutcomeRecovery {
    private WorkflowOutcomeRecovery() {}
    public static ObjectNode recover(ObjectMapper json,JsonNode receipt,Long turnId,List<ConversationMessagePO> activities) {
        if(!receipt.isObject() || receipt.path("protocol").asInt()!=1 || !receipt.path("files").isArray()
            || receipt.path("unresolvedApproval").asBoolean(true))
            throw new IllegalArgumentException("缺少可信的终态或文件检查记录，不能重新校验推进");
        if(valid(receipt,false))return ((ObjectNode)receipt).deepCopy();
        // Legacy gateway bug produced WAITING_USER without summary. Do not replace a real user question.
        if(receipt.hasNonNull("summary") || !Set.of("MISSING","WAITING_USER").contains(receipt.path("state").asText()))
            throw new IllegalArgumentException("节点回执格式无效，请核实 Agent");
        for(var message:activities) {
            if(!turnId.equals(message.getTurnId()) || !"ASSISTANT".equals(message.getRole())
                || !"ACTIVITY".equals(message.getMessageType()) || !"COMPLETED".equals(message.getStatus()))continue;
            var item=WorkflowCompletionGate.read(json,message.getContent());
            if(!"dynamicToolCall".equals(item.path("type").asText()) || !"harness_node_outcome".equals(item.path("tool").asText())
                || !"completed".equals(item.path("status").asText()) || !item.path("success").isBoolean() || !item.path("success").asBoolean())continue;
            if(message.isTruncated())throw new IllegalArgumentException("节点工具记录被截断，不能重新校验推进");
            var report=item.path("arguments");
            if(!valid(report,true))throw new IllegalArgumentException("已保存的节点工具回执无效，不能重新校验推进");
            var result=((ObjectNode)receipt).deepCopy();result.set("state",report.get("state"));result.set("summary",report.get("summary"));
            result.put("recoveredFromMessageId",message.getId());
            return result;
        }
        throw new IllegalArgumentException("未找到当前轮次的有效节点回执，请核实 Agent；无需反复补充业务信息");
    }
    private static boolean valid(JsonNode value,boolean exact) {
        return value.isObject() && (!exact || value.size()==2) && Set.of("COMPLETE","WAITING_USER").contains(value.path("state").asText())
            && value.path("summary").isTextual() && !value.path("summary").asText().isBlank() && value.path("summary").asText().length()<=16000;
    }
}
