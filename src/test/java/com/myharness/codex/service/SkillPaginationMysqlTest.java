package com.myharness.codex.service;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.vo.SkillVO;
import com.myharness.codex.mapper.SkillMapper;
import com.myharness.codex.service.impl.SkillServiceImpl;
import com.myharness.codex.support.IsolatedMysql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "mysql.isolated.integration", matches = "true")
class SkillPaginationMysqlTest {
    static IsolatedMysql mysql;
    static SkillService service;

    @BeforeAll static void database() throws Exception {
        mysql = IsolatedMysql.start();
        mysql.applyResource("db/schema.sql");
        var jdbc = new JdbcTemplate(mysql.dataSource());
        jdbc.update("INSERT INTO sys_user(id,email,password_hash,display_name,status) VALUES(901,'paging@test.local','unused','Fixture','ENABLED')");
        for (int id = 1; id <= 45; id++) {
            jdbc.update("INSERT INTO skill(id,skill_name,description,status,created_by,updated_at) VALUES(?,?,?,?,901,'2026-09-01')",
                    id, "fixture-" + id, id % 2 == 0 ? "searchable" : "", id <= 30 ? "ENABLED" : "DISABLED");
            for (int version = 1; version <= 2; version++)
                jdbc.update("INSERT INTO skill_version(skill_id,version,storage_path,sha256,file_size,status,created_by) VALUES(?,?,?,?,123,?,901)",
                        id, "v" + version, "fixture.zip", "a".repeat(64), version == 2 ? "ACTIVE" : "DISABLED");
        }
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(SkillMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setDataSource(mysql.dataSource());
        var session = new SqlSessionTemplate(factory.getObject());
        var properties = new AgentProperties();
        properties.setSkillStorageDir("target/skill-pagination-fixture");
        service = new SkillServiceImpl(session.getMapper(SkillMapper.class), properties,
                new TransactionTemplate(new DataSourceTransactionManager(mysql.dataSource())));
    }

    @AfterAll static void close() throws Exception { if (mysql != null) mysql.close(); }

    @Test void paginatesWithStableTiesAndCompleteVersionsForEachReturnedSkill() {
        var first = service.page(null, null, 1, 20);
        var second = service.page(null, null, 2, 20);
        var last = service.page(null, null, 3, 20);
        assertThat(first.total()).isEqualTo(45);
        assertThat(first.page()).isEqualTo(1);
        assertThat(first.size()).isEqualTo(20);
        assertThat(first.items()).hasSize(20);
        assertThat(first.items().getFirst().getId()).isEqualTo(45);
        assertThat(second.items()).hasSize(20);
        assertThat(second.items().getFirst().getId()).isEqualTo(25);
        assertThat(last.items()).extracting(SkillVO::getId).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(first.items()).allSatisfy(skill -> {
            assertThat(skill.getVersionCount()).isEqualTo(2);
            assertThat(skill.getVersions()).hasSize(2);
            assertThat(skill.getVersions()).allSatisfy(version -> assertThat(version.getSkillId()).isEqualTo(skill.getId()));
        });
    }

    @Test void appliesIdenticalFiltersToCountAndPageAndSupportsEmptyPages() {
        var filtered = service.page(" searchable ", "ENABLED", 2, 10);
        assertThat(filtered.total()).isEqualTo(15);
        assertThat(filtered.items()).extracting(SkillVO::getId).containsExactly(10L, 8L, 6L, 4L, 2L);
        assertThat(service.page("fixture-45", null, 1, 20).total()).isEqualTo(1);
        var empty = service.page(null, null, 4, 20);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isEqualTo(45);
        assertThat(service.page("missing", null, 1, 20).total()).isZero();
    }

    @Test void loadsOnlySelectedSkillsAcrossPagesAndRetainsCompleteOptions() {
        assertThat(service.selected(List.of(1L, 45L))).extracting(SkillVO::getId).containsExactlyInAnyOrder(1L, 45L);
        assertThat(service.list(null, "ENABLED")).hasSize(30);
        assertThatThrownBy(() -> service.selected(List.of(1L, 999L))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.selected(List.of(1L, 1L))).hasMessageContaining("不重复");
        assertThatThrownBy(() -> service.selected(List.of())).hasMessageContaining("请选择");
    }

    @Test void rejectsInvalidPageBoundsAndFilters() {
        assertThatThrownBy(() -> service.page(null, null, 0, 20)).hasMessageContaining("页码");
        assertThatThrownBy(() -> service.page(null, null, 1, 101)).hasMessageContaining("每页");
        assertThatThrownBy(() -> service.page("x".repeat(201), null, 1, 20)).hasMessageContaining("关键词");
        assertThatThrownBy(() -> service.page(null, "invalid", 1, 20)).isInstanceOf(RuntimeException.class);
    }
}
