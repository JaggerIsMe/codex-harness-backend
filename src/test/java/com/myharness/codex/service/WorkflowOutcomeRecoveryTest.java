package com.myharness.codex.service;

import com.fasterxml.jackson.databind.*;
import com.myharness.codex.entity.po.ConversationMessagePO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowOutcomeRecoveryTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode missing() throws Exception {return json.readTree("{\"protocol\":1,\"state\":\"WAITING_USER\",\"files\":[{\"path\":\"result.txt\",\"before\":\"MISSING\",\"after\":\"hash\"}],\"unresolvedApproval\":false}");}
    ConversationMessagePO activity(String state) {
        var message=new ConversationMessagePO();message.setId(42L);message.setTurnId(7L);message.setRole("ASSISTANT");message.setMessageType("ACTIVITY");message.setStatus("COMPLETED");
        var item=json.createObjectNode().put("type","dynamicToolCall").put("tool","harness_node_outcome").put("status","completed").put("success",true);
        item.putObject("arguments").put("state",state).put("summary","recorded result");message.setContent(item.toString());return message;
    }
    @Test void preservesFileEvidenceAndUsesLatestSuccessfulStructuredDeclaration() throws Exception {
        var receipt=missing();var result=WorkflowOutcomeRecovery.recover(json,receipt,7L,List.of(activity("WAITING_USER"),activity("COMPLETE")));
        assertEquals("WAITING_USER",result.path("state").asText());assertEquals(receipt.path("files"),result.path("files"));
        assertEquals(42L,result.path("recoveredFromMessageId").asLong());
        assertFalse(receipt.has("summary"));
    }
    @Test void neverReinterpretsFinalProseOldTurnsOrFailedToolsAsCompletion() throws Exception {
        for(String invalid:List.of("oldTurn","prose","failed","user","truncated")) {
            var message=activity("COMPLETE");
            switch(invalid) {
                case "oldTurn" -> message.setTurnId(6L);
                case "prose" -> message.setMessageType("TEXT");
                case "user" -> message.setRole("USER");
                case "truncated" -> message.setTruncated(true);
                case "failed" -> message.setContent(message.getContent().replace("\"success\":true","\"success\":false"));
            }
            assertThrows(IllegalArgumentException.class,()->WorkflowOutcomeRecovery.recover(json,missing(),7L,List.of(message)));
        }
    }
    @Test void explicitWaitingReceiptAndUnresolvedDecisionsCannotBeOverridden() throws Exception {
        var receipt=(com.fasterxml.jackson.databind.node.ObjectNode)missing();receipt.put("summary","Please provide the source file");
        assertEquals("WAITING_USER",WorkflowOutcomeRecovery.recover(json,receipt,7L,List.of(activity("COMPLETE"))).path("state").asText());
        receipt.put("unresolvedApproval",true);
        assertThrows(IllegalArgumentException.class,()->WorkflowOutcomeRecovery.recover(json,receipt,7L,List.of(activity("COMPLETE"))));
    }
}
