package com.myharness.codex.mapper;

import com.myharness.codex.entity.dto.ExpertRuntimeDTO;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMapperContractTest {
    @Test
    void touchingActivityRetainsConversationProjectAndOwnerScope() throws Exception {
        var method=ConversationMapper.class.getMethod("touchActivity",Long.class,Long.class,Long.class,LocalDateTime.class);
        var sql=String.join(" ",method.getAnnotation(Update.class).value());
        assertThat(sql).contains("SET last_activity_at=#{now}","WHERE id=#{id} AND project_id=#{projectId} AND user_id=#{userId}");
    }

    @Test
    void expertThreadReplacementAcceptsTheCurrentRuntimeSchema() throws Exception {
        var method=ConversationMapper.class.getMethod("replaceExpertThread",Long.class,Long.class,Long.class,
                String.class,String.class,String.class,LocalDateTime.class);
        var sql=String.join(" ",method.getAnnotation(Update.class).value()).replaceAll("\\s+","");

        assertThat(sql).contains("schemaVersion')IN(2,3,"+new ExpertRuntimeDTO().getSchemaVersion()+")");
    }

    @Test
    void compatibleRuntimeUpdateAcceptsTheCurrentRuntimeSchema() throws Exception {
        var method=ConversationMapper.class.getMethod("updateCompatibleExpertRuntime",Long.class,Long.class,Long.class,
                String.class,String.class,String.class,LocalDateTime.class);
        var sql=String.join(" ",method.getAnnotation(Update.class).value()).replaceAll("\\s+","");

        assertThat(sql).contains("schemaVersion')IN(3,"+new ExpertRuntimeDTO().getSchemaVersion()+")");
    }
}
