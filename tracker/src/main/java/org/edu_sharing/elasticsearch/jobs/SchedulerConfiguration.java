package org.edu_sharing.elasticsearch.jobs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulerConfiguration {
    @Bean(name = "cascadeScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); // adjust as needed
        scheduler.setThreadNamePrefix("cascadeScheduler-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "mainScheduler")
    public TaskScheduler mainScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); // adjust as needed
        scheduler.setThreadNamePrefix("mainScheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
