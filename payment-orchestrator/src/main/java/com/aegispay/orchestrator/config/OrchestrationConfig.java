package com.aegispay.orchestrator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrator wiring.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrchestrationProperties.class)
public class OrchestrationConfig {

    /**
     * The pool provider calls run on.
     *
     * <p><strong>Why a separate pool at all.</strong> Resilience4j's time limiter can only cancel a
     * call it did not run on the calling thread — enforcing a deadline requires the work to be
     * elsewhere. Running provider calls on the Kafka consumer threads instead would mean a hung
     * provider pins a consumer thread until its own socket timeout, and with enough hung calls the
     * consumer stops polling entirely and the whole payment stream stalls behind one bad rail.
     *
     * <p>Bounded, so a provider outage cannot spawn threads without limit. The pool size is
     * therefore also the cap on concurrent in-flight charges, which is deliberate: it is better to
     * queue behind a known limit than to discover the limit as an out-of-memory error.
     *
     * <p>Scheduled rather than fixed, because {@code TimeLimiter} needs a scheduler to fire the
     * timeout itself.
     *
     * <p>Daemon threads, so a stuck provider call cannot keep the JVM alive during shutdown. Spring
     * infers {@code shutdown()} as the destroy method, so the pool is closed with the context.
     */
    @Bean
    public ScheduledExecutorService providerCallExecutor(OrchestrationProperties properties) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "provider-call-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };

        return Executors.newScheduledThreadPool(
                properties.getProviders().getCallPoolSize(), threadFactory);
    }
}
