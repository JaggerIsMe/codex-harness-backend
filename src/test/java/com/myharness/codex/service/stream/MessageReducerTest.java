package com.myharness.codex.service.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.codex.entity.po.ConversationMessagePO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageReducerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MessageReducer reducer=new MessageReducer();
    private ConversationMessagePO message(){ConversationMessagePO m=new ConversationMessagePO();m.setContent("");m.setStatus("STREAMING");return m;}
    private ObjectNode event(String type,String body){return json.createObjectNode().put("eventType",type).put("itemId","answer").put("content",body);}
    @Test void preservesWhitespaceAndReplacesFinalSnapshot() {
        ConversationMessagePO m=message();
        reducer.apply(m,event("AGENT_MESSAGE_DELTA","hello"),1);
        reducer.apply(m,event("AGENT_MESSAGE_DELTA"," \n"),2);
        assertEquals("hello \n",m.getContent());
        ObjectNode complete=json.createObjectNode().put("eventType","ITEM_COMPLETED").put("itemId","answer");
        complete.putObject("details").put("type","agentMessage").put("text","hello \nworld").put("phase","final_answer");
        assertEquals("REPLACE",reducer.apply(m,complete,3).getOperation());
        assertEquals("hello \nworld",m.getContent());
        assertEquals("COMPLETED",m.getStatus());
        assertFalse(m.getMetadata().contains("world"));
        assertThrows(IllegalArgumentException.class,() -> reducer.apply(m,event("AGENT_MESSAGE_DELTA","late"),4));
    }
    @Test void preservesIdentityWhenPhaseArrivesLate() {
        ObjectNode early=event("AGENT_MESSAGE_DELTA","a");
        ObjectNode late=early.deepCopy().put("phase","commentary");
        assertEquals(reducer.key(1L,early,reducer.type(early)),reducer.key(1L,late,reducer.type(late)));
        ConversationMessagePO m=message();m.setPhase("commentary");
        reducer.apply(m,early,1);
        assertEquals("COMMENTARY",m.getMessageType());
    }
    @Test void replacesTurnDiffAndMarksBoundedOutput() {
        ConversationMessagePO m=message();
        reducer.apply(m,event("TURN_DIFF_UPDATED","old"),1);
        reducer.apply(m,event("TURN_DIFF_UPDATED","new"),2);
        assertEquals("new",m.getContent());
        ConversationMessagePO output=message();
        char[] large=new char[300000];java.util.Arrays.fill(large,'x');
        reducer.apply(output,event("COMMAND_OUTPUT_DELTA",new String(large)),1);
        assertEquals(262144,output.getContent().length());assertTrue(output.isTruncated());
    }
    @Test void rejectsUnidentifiableDeltas() {
        ObjectNode delta=event("AGENT_MESSAGE_DELTA","x");delta.remove("itemId");
        assertThrows(IllegalArgumentException.class,() -> reducer.key(1L,delta,"TEXT"));
    }
}
