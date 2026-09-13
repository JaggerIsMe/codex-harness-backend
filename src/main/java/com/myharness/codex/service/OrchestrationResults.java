package com.myharness.codex.service;

import com.myharness.codex.entity.po.ConversationMessagePO;
import com.myharness.codex.entity.po.ConversationTurnPO;
import com.myharness.codex.entity.vo.OrchestrationStepResultVO;
import java.util.List;

/** Extracts traceable final output without adding instructions to the user's message. */
public final class OrchestrationResults {
    private OrchestrationResults() {}
    public static OrchestrationStepResultVO result(ConversationTurnPO turn,List<ConversationMessagePO> messages) {
        var candidates=messages.stream().filter(m -> "TEXT".equals(m.getMessageType()) && !"commentary".equals(m.getPhase())).toList();
        if(candidates.isEmpty()) throw new IllegalArgumentException("Turn 没有完整的最终文本，无法交接结果");
        var last=candidates.stream().max(java.util.Comparator.comparing(ConversationMessagePO::getSequenceNo,
            java.util.Comparator.nullsFirst(Long::compareTo))).orElseThrow();
        if(!"COMPLETED".equals(last.getStatus()) || last.getContent()==null || last.getContent().isBlank())
            throw new IllegalArgumentException("最终文本不完整，无法交接结果");
        if(last.isTruncated()) throw new IllegalArgumentException("最终文本已被截断，无法交接结果");
        if(last.getContent().length()>16000) throw new IllegalArgumentException("交接结果超过 16000 字符，请在会话中核实");
        return new OrchestrationStepResultVO(1,last.getContent(),List.of(last.getId()),turn.getId(),turn.getExpertVersionId(),false);
    }
}
