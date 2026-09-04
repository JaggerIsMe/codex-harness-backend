package com.myharness.codex.service.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.codex.entity.po.ConversationMessagePO;
import com.myharness.codex.entity.vo.MessagePatchVO;
import com.myharness.codex.security.SecureDigests;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** Pure aggregation rules; transport and persistence do not infer text semantics. */
@Component
public class MessageReducer {
    public String type(JsonNode event) {
        String kind=event.path("eventType").asText();
        String itemType=event.path("details").path("type").asText();
        if (kind.equals("AGENT_MESSAGE_DELTA") || itemType.equals("agentMessage"))
            return "commentary".equals(event.path("phase").asText(event.path("details").path("phase").asText())) ? "COMMENTARY" : "TEXT";
        if (kind.equals("PLAN_DELTA") || itemType.equals("plan")) return "REASONING";
        if (kind.startsWith("COMMAND_OUTPUT_")) return "COMMAND_OUTPUT";
        if (kind.startsWith("COMMAND_")) return "COMMAND";
        if (kind.equals("FILE_CHANGE_DELTA")) return "COMMAND_OUTPUT";
        if (kind.startsWith("FILE_CHANGE") || kind.equals("TURN_DIFF_UPDATED")) return "FILE_CHANGE";
        return kind.equals("WARNING") ? "ERROR" : "ACTIVITY";
    }

    public String key(Long turnId,JsonNode event,String type) {
        String item=event.path("itemId").asText("");
        if (item.isEmpty()) item=event.path("details").path("id").asText("");
        if (item.isEmpty()) {
            if (event.path("eventType").asText().equals("TURN_DIFF_UPDATED")) item="turn-diff";
            else if (event.path("eventType").asText().endsWith("_DELTA")) throw new IllegalArgumentException("Stream delta requires itemId");
            else item="notification-"+event.path("eventSeq").asLong();
        }
        // phase can arrive only at item completion: TEXT and COMMENTARY share one identity.
        String channel=(type.equals("TEXT") || type.equals("COMMENTARY")) ? "AGENT_MESSAGE" : type;
        return SecureDigests.sha256(turnId+"\n"+item+"\n"+channel);
    }

    public MessagePatchVO apply(ConversationMessagePO message,JsonNode event,long revision) {
        long base=message.getRevision();
        String kind=event.path("eventType").asText();
        boolean delta=kind.endsWith("_DELTA");
        boolean completed=kind.endsWith("_COMPLETED");
        if (!"STREAMING".equals(message.getStatus()) && delta)
            throw new IllegalArgumentException("Delta received after item completion");
        String before=message.getContent()==null ? "" : message.getContent();
        String type=type(event);
        // A delta without phase retains the phase established by item/started.
        if (type.equals("TEXT") && "commentary".equals(message.getPhase()) && !event.hasNonNull("phase")) type="COMMENTARY";
        message.setMessageType(type);
        String phase=event.path("phase").asText(event.path("details").path("phase").asText(null));
        if(phase!=null && phase.length()>32) throw new IllegalArgumentException("Message phase too long");
        if (phase!=null) message.setPhase(phase);
        JsonNode details=event.path("details");
        String body=event.hasNonNull("content") ? event.get("content").asText() : null;
        if (!delta && details.isObject()) {
            if (details.hasNonNull("text")) body=details.get("text").asText();
            if (type.equals("COMMAND_OUTPUT") && details.hasNonNull("aggregatedOutput")) body=details.get("aggregatedOutput").asText();
            ObjectNode metadata=((ObjectNode)details).deepCopy();
            metadata.remove(java.util.Arrays.asList("text","aggregatedOutput","output","diff"));
            String metadataJson=metadata.toString();
            if(metadataJson.length()>65536) {
                message.setTruncated(true);
                ObjectNode summary=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                summary.put("truncated",true);
                summary.put("type",metadata.path("type").asText().substring(0,Math.min(128,metadata.path("type").asText().length())));
                metadata=summary; metadataJson=summary.toString();
            }
            message.setMetadata(metadataJson);
            if (body==null && !(type.equals("TEXT") || type.equals("COMMENTARY") || type.equals("REASONING") || type.equals("COMMAND_OUTPUT")))
                body=metadata.toString();
        }
        if (body==null) body=delta ? "" : before;
        int max=(type.equals("TEXT") || type.equals("COMMENTARY") || type.equals("REASONING")) ? 1048576 : 262144;
        String content=limit(delta ? before+body : body,max,message);
        message.setContent(content); message.setRevision(revision); message.setUpdatedAt(LocalDateTime.now());
        if (completed || kind.equals("WARNING")) {
            message.setStatus("COMPLETED"); message.setCompletedAt(message.getUpdatedAt());
        }
        // First patch is always self-contained, allowing a client to discover a new Message.
        boolean append=delta && base>0 && content.startsWith(before);
        return new MessagePatchVO(message,base,append ? "APPEND" : "REPLACE",append ? content.substring(before.length()) : content);
    }

    private String limit(String value,int max,ConversationMessagePO message) {
        if (value.length()<=max) return value;
        message.setTruncated(true);
        int end=Character.isHighSurrogate(value.charAt(max-1)) ? max-1 : max;
        return value.substring(0,end);
    }
}
