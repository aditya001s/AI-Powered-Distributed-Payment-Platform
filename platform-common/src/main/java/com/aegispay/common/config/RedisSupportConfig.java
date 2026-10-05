package com.aegispay.common.config;

import com.aegispay.common.idempotency.IdempotencyGuard;
import com.aegispay.common.lock.DistributedLock;
import com.aegispay.common.lock.RedisDistributedLock;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers the Redis-backed primitives, but only when Redis is actually
 * configured.
 *
 * <p>{@link ConditionalOnBean} on {@code StringRedisTemplate} keys off Spring
 * Boot's own Redis autoconfiguration, so a service that does not set
 * {@code spring.data.redis.*} simply does not get these beans instead of failing
 * to start.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisSupportConfig {

    @Bean
    @ConditionalOnMissingBean
    public DistributedLock distributedLock(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        return new RedisDistributedLock(redis, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyGuard idempotencyGuard(StringRedisTemplate redis) {
        return new IdempotencyGuard(redis);
    }
}
