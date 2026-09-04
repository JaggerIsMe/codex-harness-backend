package com.myharness.codex.service.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataAccessException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Single-backend deployment: all stream mutations and recovery reads cross this seam. */
@Service
public class ConversationMessageStream {
    private static final Logger LOG=LoggerFactory.getLogger(ConversationMessageStream.class);
    private final Object[] locks=new Object[256];
    private final ConversationMapper mapper;
    private final MessageStreamStore store;
    private final MessageReducer reducer;
    private final TransactionTemplate transactions;
    private final ClientEventWebSocketHandler clients;
    public ConversationMessageStream(ConversationMapper mapper,MessageStreamStore store,MessageReducer reducer,
                                     TransactionTemplate transactions,ClientEventWebSocketHandler clients) {
        this.mapper=mapper; this.store=store; this.reducer=reducer; this.transactions=transactions; this.clients=clients;
        Arrays.setAll(locks,i -> new Object());
    }
    private Object lock(Long id){return locks[Math.floorMod(id.hashCode(),locks.length)];}
    private boolean active(ConversationTurnPO turn){return Arrays.asList("CREATED","RUNNING","WAITING_APPROVAL").contains(turn.getStatus());}

    public boolean accept(Long deviceId,JsonNode event) {
        Long conversationId=requiredId(event,"conversationId"), turnId=requiredId(event,"turnId");
        long seq=event.path("eventSeq").asLong(0);
        if(seq<=0) throw new IllegalArgumentException("TURN_EVENT requires positive eventSeq; upgrade Agent");
        synchronized(lock(conversationId)) {
            ConversationPO conversation=requireDevice(deviceId,conversationId,turnId);
            ConversationTurnPO turn=mapper.selectTurn(turnId);
            if(!active(turn) || store.closed(turnId)) return false;
            long previous=store.cursor(turnId);
            if(seq<=previous) return false;
            if(seq!=previous+1) throw new IllegalArgumentException("TURN_EVENT sequence gap");
            List<ConversationMessagePO> changed=new ArrayList<>();
            List<MessagePatchVO> patches=new ArrayList<>();
            apply(conversationId,turnId,event,seq,changed,patches);
            String kind=event.path("eventType").asText();
            if(kind.equals("COMMAND_COMPLETED") || kind.equals("FILE_CHANGE_COMPLETED")) {
                ObjectNode output=((ObjectNode)event).deepCopy(); output.put("eventType","COMMAND_OUTPUT_COMPLETED");
                String key=reducer.key(turnId,output,"COMMAND_OUTPUT");
                if(store.message(turnId,key)!=null || event.path("details").hasNonNull("aggregatedOutput"))
                    apply(conversationId,turnId,output,seq,changed,patches);
            }
            MessageUpdateVO update=new MessageUpdateVO(conversationId,turnId,seq,patches);
            if(!store.append(update,changed)) return false;
            List<ConversationMessagePO> complete=changed.stream().filter(m -> !"STREAMING".equals(m.getStatus())).collect(Collectors.toList());
            // Redis retains dirty snapshots on SQL failure; the scheduled checkpoint retries.
            if(!complete.isEmpty()) transactions.execute(status -> {complete.forEach(mapper::saveLogicalMessage); return null;});
            Map<String,Object> frame=new LinkedHashMap<>(); frame.put("type","MESSAGE_UPDATED"); frame.put("payload",update);
            clients.sendToUser(conversation.getUserId(),frame);
            return true;
        }
    }

    private void apply(Long conversationId,Long turnId,JsonNode event,long seq,List<ConversationMessagePO> changed,List<MessagePatchVO> patches) {
        String type=reducer.type(event), key=reducer.key(turnId,event,type);
        ConversationMessagePO message=store.message(turnId,key);
        if(message==null) {
            message=transactions.execute(status -> {
                mapper.lockConversation(conversationId);
                ConversationMessagePO existing=mapper.selectLogicalMessage(conversationId,key);
                if(existing!=null) return existing;
                ConversationMessagePO value=new ConversationMessagePO();
                value.setConversationId(conversationId); value.setTurnId(turnId); value.setMessageKey(key);
                value.setItemId(event.path("itemId").asText(event.path("details").path("id").asText(null)));
                if(value.getItemId()!=null && value.getItemId().length()>128) throw new IllegalArgumentException("itemId too long");
                value.setSequenceNo(mapper.nextSequence(conversationId)); value.setRole("ASSISTANT"); value.setMessageType(type);
                value.setContent(""); value.setStatus("STREAMING"); value.setCreatedAt(LocalDateTime.now());
                mapper.insertLogicalMessage(value); return value;
            });
        }
        patches.add(reducer.apply(message,event,seq)); changed.add(message);
    }

    public void finish(Long deviceId,Long conversationId,Long turnId,String status,Long lastEventSeq) {
        synchronized(lock(conversationId)) {
            requireDevice(deviceId,conversationId,turnId);
            long cursor;
            try {cursor=store.cursor(turnId);} catch(DataAccessException exception) {cursor=-1;}
            boolean gap=cursor<0 || (lastEventSeq!=null && lastEventSeq!=cursor);
            finishSnapshots(conversationId,turnId,status,gap);
        }
    }

    private void finishSnapshots(Long conversationId,Long turnId,String status,boolean gap) {
        Map<String,ConversationMessagePO> merged=new LinkedHashMap<>();
        for(ConversationMessagePO value:mapper.selectTurnMessages(turnId)) merged.put(value.getMessageKey(),value);
        boolean redisAvailable=true;
        long cursor=0;
        List<ConversationMessagePO> snapshots=Collections.emptyList();
        try {snapshots=store.messages(turnId);cursor=store.cursor(turnId);}
        catch(DataAccessException exception) {redisAvailable=false;LOG.error("Saving SQL checkpoints as incomplete; Redis unavailable for Turn {}",turnId);}
        for(ConversationMessagePO value:snapshots) {
            ConversationMessagePO old=merged.get(value.getMessageKey());
            if(old==null || value.getRevision()>=old.getRevision()) merged.put(value.getMessageKey(),value);
        }
        List<ConversationMessagePO> values=new ArrayList<>(merged.values());
        for(ConversationMessagePO value:values) {
            if("STREAMING".equals(value.getStatus()) || gap) {
                boolean finalDiff="FILE_CHANGE".equals(value.getMessageType()) && value.getItemId()==null && "COMPLETED".equals(status) && !gap;
                value.setStatus(finalDiff ? "COMPLETED" : ("INTERRUPTED".equals(status) && !gap ? "INTERRUPTED" : "INCOMPLETE"));
                value.setRevision(Math.max(value.getRevision(),cursor)+1);
                value.setUpdatedAt(LocalDateTime.now()); value.setCompletedAt(value.getUpdatedAt());
            }
        }
        // Freeze before SQL: failed SQL leaves a discoverable, retryable final snapshot.
        if(redisAvailable) try {store.close(conversationId,turnId,values,status);}
        catch(DataAccessException exception) {redisAvailable=false;LOG.error("Redis freeze failed for Turn {}; preserving database checkpoints",turnId);}
        final boolean cleanup=redisAvailable;
        transactions.execute(tx -> {
            values.forEach(mapper::saveLogicalMessage);
            if(cleanup) afterCommit(() -> {store.expire(turnId); store.removePending(conversationId+":"+turnId);});
            return null;
        });
    }

    public MessageStateVO state(Long conversationId,long before,int limit,Long requestedTurn,long after) {
        synchronized(lock(conversationId)) {
            int pageSize=Math.max(1,Math.min(limit,200));
            ConversationTurnPO turn=mapper.selectLatestTurn(conversationId);
            List<ConversationMessagePO> page=mapper.selectMessagePage(conversationId,before,pageSize+1);
            boolean hasMore=page.size()>pageSize;
            if(hasMore) page=new ArrayList<>(page.subList(0,pageSize));
            Map<Long,ConversationMessagePO> messages=new LinkedHashMap<>();
            for(ConversationMessagePO value:page) messages.put(value.getId(),value);
            // A long-lived item may precede the latest history page but still receive deltas.
            // Always include all active-Turn bases, otherwise clients could never recover it.
            if(before==0 && turn!=null && active(turn))
                for(ConversationMessagePO value:mapper.selectTurnMessages(turn.getId())) messages.put(value.getId(),value);
            boolean degraded=false, reset=after>=0; long cursor=0;
            List<JsonNode> updates=Collections.emptyList();
            if(turn!=null) try {
                cursor=store.cursor(turn.getId());
                for(ConversationMessagePO value:store.messages(turn.getId())) {
                    ConversationMessagePO old=messages.get(value.getId());
                    if((old!=null && value.getRevision()>=old.getRevision()) || (before==0 && active(turn) && old==null)) messages.put(value.getId(),value);
                }
                if(after>=0 && turn.getId().equals(requestedTurn) && after<=cursor && !store.closed(turn.getId())) {
                    updates=store.replay(turn.getId(),after);
                    long expected=after+1;
                    boolean contiguous=true;
                    for(JsonNode update:updates) if(update.path("cursor").asLong()!=expected++) {contiguous=false;break;}
                    reset=!contiguous || expected-1!=cursor;
                }
                // Lost Redis state must not masquerade as a complete active snapshot.
                if(active(turn) && cursor==0 && page.stream().anyMatch(m -> turn.getId().equals(m.getTurnId()) && m.getMessageKey()!=null)) degraded=true;
            } catch(DataAccessException exception) {degraded=true; reset=true; LOG.warn("Redis unavailable during message recovery for {}",conversationId);}
            List<ConversationMessageVO> result=messages.values().stream().sorted(Comparator.comparing(ConversationMessagePO::getSequenceNo)).map(ConversationMessageVO::new).collect(Collectors.toList());
            return new MessageStateVO(result,turn==null ? null : turn.getId(),cursor,hasMore,degraded,reset,reset ? Collections.emptyList() : updates);
        }
    }

    @Scheduled(fixedDelayString="${harness.message-stream.checkpoint-ms:5000}")
    public void checkpoint() {
        Set<String> pending;
        try {pending=store.pending();} catch(RuntimeException exception) {LOG.warn("Message checkpoint cannot access Redis",exception);return;}
        for(String member:pending) {
            try {
                String[] ids=member.split(":"); Long conversationId=Long.valueOf(ids[0]),turnId=Long.valueOf(ids[1]);
                synchronized(lock(conversationId)) {
                    ConversationTurnPO turn=mapper.selectTurn(turnId);
                    if(turn==null){store.expire(turnId);store.removePending(member);continue;}
                    if(!active(turn) || store.closed(turnId)) {
                        String target=active(turn) ? store.terminalStatus(turnId) : turn.getStatus();
                        transactions.execute(tx -> {
                            finishSnapshots(conversationId,turnId,target,false);
                            if(active(turn)) {
                                ConversationPO conversation=mapper.selectConversation(conversationId);
                                mapper.finishTurn(turnId,conversationId,conversation.getDeviceId(),target,"MESSAGE_RECOVERY","Recovered pending terminal persistence",LocalDateTime.now());
                            }
                            return null;
                        });
                        continue;
                    }
                    Map<String,Long> persisted=new HashMap<>();
                    for(ConversationMessagePO value:mapper.selectTurnMessages(turnId)) persisted.put(value.getMessageKey(),value.getRevision());
                    List<ConversationMessagePO> dirty=store.messages(turnId).stream().filter(value -> value.getRevision()>persisted.getOrDefault(value.getMessageKey(),-1L)).collect(Collectors.toList());
                    if(!dirty.isEmpty()) transactions.execute(tx -> {dirty.forEach(mapper::saveLogicalMessage);return null;});
                }
            } catch(RuntimeException exception) {LOG.error("Message checkpoint will retry {}",member,exception);}
        }
    }

    private ConversationPO requireDevice(Long deviceId,Long conversationId,Long turnId) {
        ConversationPO conversation=mapper.selectConversation(conversationId);
        ConversationTurnPO turn=mapper.selectTurn(turnId);
        if(conversation==null || !deviceId.equals(conversation.getDeviceId()) || turn==null || !conversationId.equals(turn.getConversationId()))
            throw new IllegalArgumentException("Unknown Conversation or Turn for Device");
        return conversation;
    }
    private Long requiredId(JsonNode event,String field) {
        long value=event.path(field).asLong(0); if(value<=0) throw new IllegalArgumentException("Invalid "+field); return value;
    }
    private void afterCommit(Runnable action) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()){action.run();return;}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){action.run();}});
    }
}
