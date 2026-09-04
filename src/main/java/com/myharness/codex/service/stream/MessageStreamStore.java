package com.myharness.codex.service.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.codex.entity.po.ConversationMessagePO;
import com.myharness.codex.entity.vo.MessageUpdateVO;
import java.util.List;
import java.util.Set;

/** Internal storage seam: snapshots and replay entries are committed atomically. */
public interface MessageStreamStore {
    long cursor(Long turnId);
    boolean closed(Long turnId);
    ConversationMessagePO message(Long turnId,String key);
    List<ConversationMessagePO> messages(Long turnId);
    boolean append(MessageUpdateVO update,List<ConversationMessagePO> messages);
    void close(Long conversationId,Long turnId,List<ConversationMessagePO> messages,String status);
    String terminalStatus(Long turnId);
    void expire(Long turnId);
    Set<String> pending();
    void removePending(String member);
    List<JsonNode> replay(Long turnId,long after);
}
