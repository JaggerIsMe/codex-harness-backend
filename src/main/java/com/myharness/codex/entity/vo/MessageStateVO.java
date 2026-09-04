package com.myharness.codex.entity.vo;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

public class MessageStateVO {
    private final List<ConversationMessageVO> messages;
    private final Long turnId;
    private final long cursor;
    private final boolean hasMore;
    private final boolean degraded;
    private final boolean resetRequired;
    private final List<JsonNode> updates;
    public MessageStateVO(List<ConversationMessageVO> messages,Long turnId,long cursor,boolean hasMore,
                          boolean degraded,boolean resetRequired,List<JsonNode> updates) {
        this.messages=messages; this.turnId=turnId; this.cursor=cursor; this.hasMore=hasMore;
        this.degraded=degraded; this.resetRequired=resetRequired; this.updates=updates;
    }
    public List<ConversationMessageVO> getMessages(){return messages;}
    public Long getTurnId(){return turnId;}
    public long getCursor(){return cursor;}
    public boolean isHasMore(){return hasMore;}
    public boolean isDegraded(){return degraded;}
    public boolean isResetRequired(){return resetRequired;}
    public List<JsonNode> getUpdates(){return updates;}
}
