package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.ConversationTurnPO;

public class TurnVO {
    private final String preparationPhase;
    public String getPreparationPhase(){return preparationPhase;}
    private final Long id;
    private final Long conversationId;
    private final String status;
    private final String codexTurnId;
    public TurnVO(ConversationTurnPO value) {
        preparationPhase=value.getPreparationPhase(); id=value.getId(); conversationId=value.getConversationId(); status=value.getStatus(); codexTurnId=value.getCodexTurnId();
    }
    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public String getStatus() { return status; }
    public String getCodexTurnId() { return codexTurnId; }
}
