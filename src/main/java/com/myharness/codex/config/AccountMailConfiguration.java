package com.myharness.codex.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MailProperties.class, TaskSchedulingProperties.class})
public class AccountMailConfiguration {
    @Bean("accountMailClock")
    @ConditionalOnMissingBean(name = "accountMailClock")
    public Clock accountMailClock() { return Clock.systemUTC(); }

    // Providing only the mail scheduler would make Spring route unqualified @Scheduled
    // methods onto that same scheduler. Keep an explicit conventional default as well.
    @Bean("taskScheduler")
    @Primary
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(TaskSchedulingProperties properties) {
        ThreadPoolTaskSchedulerBuilder builder = new ThreadPoolTaskSchedulerBuilder()
                .poolSize(properties.getPool().getSize())
                .threadNamePrefix(properties.getThreadNamePrefix())
                .awaitTermination(properties.getShutdown().isAwaitTermination());
        if (properties.getShutdown().getAwaitTerminationPeriod() != null) {
            builder = builder.awaitTerminationPeriod(properties.getShutdown().getAwaitTerminationPeriod());
        }
        return builder.build();
    }

    @Bean("accountMailScheduler")
    @ConditionalOnMissingBean(name = "accountMailScheduler")
    public ThreadPoolTaskScheduler accountMailScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskSchedulerBuilder()
                .poolSize(1).threadNamePrefix("account-mail-").awaitTermination(false).build();
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
