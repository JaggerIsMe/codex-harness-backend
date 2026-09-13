package com.myharness.codex.mapper;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationConversationQueryTest {
    @Test void ordinaryListAndCountExcludeAllStepHistoryBeforePaginationAndRetainOwnership() {
        var config=new Configuration();config.addMapper(OrchestrationMapper.class);
        var parameters=Map.of("projectId",2L,"userId",3L,"keyword","search","limit",10,"offset",10L);
        for(var method:List.of("ordinaryConversations","countOrdinaryConversations")) {
            var statement=config.getMappedStatement(OrchestrationMapper.class.getName()+"."+method).getBoundSql(parameters);
            assertThat(statement.getSql()).contains("WHERE c.user_id=? AND c.project_id=?",
                    "AND NOT EXISTS(SELECT 1 FROM orchestration_step s WHERE s.conversation_id=c.id)");
            assertThat(statement.getSql()).doesNotContain("orchestration_execution");
            assertThat(statement.getParameterMappings().get(0).getProperty()).isEqualTo("userId");
        }
        var statuses=config.getMappedStatement(OrchestrationMapper.class.getName()+".managedConversations")
                .getBoundSql(Map.of("ids",List.of(6L,10L)));
        assertThat(statuses.getParameterMappings()).hasSize(2);
        assertThat(statuses.getSql()).doesNotContain("status");
    }
}
