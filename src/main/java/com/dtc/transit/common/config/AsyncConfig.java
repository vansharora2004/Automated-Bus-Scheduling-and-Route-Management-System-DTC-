package com.dtc.transit.common.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background execution for work that must not block a request.
 *
 * <p>A bounded queue rather than an unbounded one. An unbounded queue turns a burst of work into
 * growing latency and eventually an out-of-memory failure, where a bounded one rejects and says so.
 * The caller-runs policy then applies backpressure instead of discarding work.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("applicationTaskExecutor")
    public Executor applicationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
