package com.myharness.codex.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspacePaginationMapperTest {
    private final Configuration configuration=configuration();

    @Test
    void listAndCountBindTheSameScopedLiteralKeywordWithoutInterpolatingItIntoSql() {
        for(String mapper:List.of("ProjectMapper","ConversationMapper")) {
            String select=mapper.equals("ProjectMapper")?"selectOwnedProjects":"selectProjectConversations";
            String count=mapper.equals("ProjectMapper")?"countOwnedProjects":"countProjectConversations";
            var args=Map.of("userId",7L,"projectId",9L,"keyword","' OR 1=1 --","limit",20,"offset",120L);
            var page=bound(mapper+"."+select,args);
            var total=bound(mapper+"."+count,args);
            assertThat(page.getSql()).contains("LIMIT ? OFFSET ?").doesNotContain("LIMIT 100","' OR 1=1 --");
            assertThat(total.getSql()).doesNotContain("LIMIT","OFFSET","' OR 1=1 --");
            assertThat(page.getParameterMappings().stream().map(value -> value.getProperty()).toList())
                    .containsAll(total.getParameterMappings().stream().map(value -> value.getProperty()).toList())
                    .endsWith("limit","offset");
            assertThat(total.getParameterMappings().stream().map(value -> value.getProperty()).toList())
                    .contains("userId","keyword");
        }
    }

    @Test
    void statusIdsExpandToBoundParametersWithinBothOwnershipFilters() {
        var sql=bound("ConversationMapper.selectConversationStatuses",Map.of("userId",7L,"projectId",9L,"ids",List.of(101L,2L)));
        assertThat(sql.getSql()).contains("c.user_id=? AND c.project_id=?","c.id IN");
        assertThat(sql.getParameterMappings()).hasSize(4);
        var firstId=sql.getParameterMappings().get(2).getProperty();
        var secondId=sql.getParameterMappings().get(3).getProperty();
        assertThat(sql.getAdditionalParameter(firstId)).isEqualTo(101L);
        assertThat(sql.getAdditionalParameter(secondId)).isEqualTo(2L);
    }

    @Test
    void emptyStatusIdsProduceAnAlwaysFalsePredicateRatherThanReadingEveryConversation() {
        var sql=bound("ConversationMapper.selectConversationStatuses",Map.of("userId",7L,"projectId",9L,"ids",List.of()));
        assertThat(sql.getSql()).contains("AND 1=0").doesNotContain("c.id IN");
        assertThat(sql.getParameterMappings()).hasSize(2);
    }

    private BoundSql bound(String method,Map<String,?> arguments) {
        return configuration.getMappedStatement("com.myharness.codex.mapper."+method).getBoundSql(arguments);
    }

    private static Configuration configuration() {
        var configuration=new Configuration();
        configuration.addMapper(ProjectMapper.class);configuration.addMapper(ConversationMapper.class);
        return configuration;
    }
}
