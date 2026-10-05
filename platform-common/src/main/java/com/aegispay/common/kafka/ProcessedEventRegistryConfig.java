package com.aegispay.common.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Supplies a {@link ProcessedEventRegistry}: Redis-backed when Redis is
 * available, otherwise a no-op.
 *
 * <p>The no-op fallback is a conscious choice. Consumer handlers are written to be
 * idempotent on their own, so losing de-duplication degrades efficiency (an
 * occasional redundant replay) rather than correctness. That keeps a service
 * startable in a constrained environment — a unit test, or a local run without
 * Redis — instead of refusing to boot.
 */
@Configuration(proxyBeanMethods = false)
public class ProcessedEventRegistryConfig {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventRegistryConfig.class);

    @Bean
    @ConditionalOnMissingBean(ProcessedEventRegistry.class)
    public ProcessedEventRegistry noOpProcessedEventRegistry() {
        log.warn("Redis is not configured: Kafka consumer de-duplication is DISABLED. "
                + "Handlers must be idempotent on their own. This is expected in unit tests, "
                + "but every deployed environment should provide Redis.");
        return new NoOpProcessedEventRegistry();
    }

    /**
     * Isolates every Redis-linked signature in a class that Spring can skip without
     * loading when Spring Data Redis is absent from a service's runtime classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisRegistryConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(ProcessedEventRegistry.class)
        ProcessedEventRegistry redisProcessedEventRegistry(StringRedisTemplate redis) {
            return new RedisProcessedEventRegistry(redis);
        }

        /**
         * Reads a completed marker before dispatch and writes it only after the
         * handler's transaction has returned successfully.
         */
        static class RedisProcessedEventRegistry implements ProcessedEventRegistry {

            private static final String KEY_PREFIX = "aegis:evt:processed:";

            private final StringRedisTemplate redis;

            RedisProcessedEventRegistry(StringRedisTemplate redis) {
                this.redis = redis;
            }

            @Override
            public boolean isProcessed(String consumerName, String eventId) {
                if (eventId == null || eventId.isBlank()) {
                    return false;
                }
                return Boolean.TRUE.equals(redis.hasKey(key(consumerName, eventId)));
            }

            @Override
            public void markProcessed(String consumerName, String eventId, Duration ttl) {
                if (eventId != null && !eventId.isBlank()) {
                    redis.opsForValue().set(key(consumerName, eventId), "1", ttl);
                }
            }

            private String key(String consumerName, String eventId) {
                return KEY_PREFIX + consumerName + ":" + eventId;
            }
        }
    }

    /** Never reports a completed marker and ignores successful marks. */
    static class NoOpProcessedEventRegistry implements ProcessedEventRegistry {
        @Override
        public boolean isProcessed(String consumerName, String eventId) {
            return false;
        }

        @Override
        public void markProcessed(String consumerName, String eventId, Duration ttl) {
            // nothing to record
        }
    }
}
