package com.tenderpocket.config;

import com.tenderpocket.scheduler.SyncFailureAlertService;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.TaskUtils;

/**
 * Any scheduled task that throws (instead of handling its own error) is still logged as before,
 * and the tech team gets an email with the stack trace.
 */
@Configuration
public class SchedulerErrorAlertConfig {

    @Bean
    public ThreadPoolTaskSchedulerCustomizer schedulerErrorAlerts(SyncFailureAlertService alerts) {
        return scheduler -> scheduler.setErrorHandler(t -> {
            TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.handleError(t);
            try {
                alerts.reportUncaught(t);
            } catch (Exception ignored) {
                // never let alerting break the scheduler
            }
        });
    }
}
