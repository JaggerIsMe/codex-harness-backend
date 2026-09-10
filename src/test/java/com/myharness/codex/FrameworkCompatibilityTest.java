package com.myharness.codex;

import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.SysUserMapper;
import jakarta.validation.Validator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FrameworkCompatibilityTest {
    @Test
    void loadsMappersJakartaValidationAndRedisConfigurationWithoutExternalWrites() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MybatisAutoConfiguration.class,
                        RedisAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withUserConfiguration(MapperConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(Runtime.version().feature()).isEqualTo(21);
                    assertThat(SpringBootVersion.getVersion()).startsWith("3.5.");
                    assertThat(SqlSessionFactory.class.getPackage().getImplementationVersion()).startsWith("3.5.");
                    assertThat(context).hasSingleBean(SqlSessionFactory.class)
                            .hasSingleBean(SysUserMapper.class).hasSingleBean(ConversationMapper.class)
                            .hasSingleBean(Validator.class).hasSingleBean(StringRedisTemplate.class);
                    var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
                    assertThat(configuration.isMapUnderscoreToCamelCase()).isTrue();
                    assertThat(configuration.hasStatement("com.myharness.codex.mapper.SysUserMapper.selectByEmail")).isTrue();
                    assertThat(configuration.hasMapper(ConversationMapper.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("spring.redis.host")).isNull();
                    assertThat(context.getEnvironment().getProperty("spring.data.redis.host")).isNotBlank();
                    assertThat(context.getBean(RedisProperties.class).getTimeout()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.myharness.codex.mapper")
    static class MapperConfiguration {
    }
}
