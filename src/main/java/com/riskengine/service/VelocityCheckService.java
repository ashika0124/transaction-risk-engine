package com.riskengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed sliding-window rate limiter across user/device/merchant
 * dimensions.
 * Uses ZSET (sorted set) with timestamps as scores for O(log N) operations.
 */
@Service
@Slf4j
public class VelocityCheckService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${risk.engine.velocity.window-seconds:120}")
    private int windowSeconds;

    @Value("${risk.engine.velocity.user-threshold:5}")
    private int userThreshold;

    @Value("${risk.engine.velocity.device-threshold:10}")
    private int deviceThreshold;

    @Value("${risk.engine.velocity.merchant-threshold:15}")
    private int merchantThreshold;

    public VelocityCheckService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Check velocity for all dimensions and return a composite risk score.
     * Returns 0-100 where higher means more suspicious.
     */
    public VelocityResult check(String userId, String deviceId, String merchantId) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - (windowSeconds * 1000L);

        int userCount = addAndCount("velocity:user:" + userId, now, windowStart);
        int deviceCount = addAndCount("velocity:device:" + deviceId, now, windowStart);
        int merchantCount = addAndCount("velocity:merchant:" + merchantId, now, windowStart);

        boolean userFlagged = userCount > userThreshold;
        boolean deviceFlagged = deviceCount > deviceThreshold;
        boolean merchantFlagged = merchantCount > merchantThreshold;

        int velocityScore = 0;
        if (userFlagged)
            velocityScore += 40;
        if (deviceFlagged)
            velocityScore += 30;
        if (merchantFlagged)
            velocityScore += 20;

        // Scale score based on how far over threshold
        if (userFlagged) {
            velocityScore = Math.min(velocityScore + (userCount - userThreshold) * 5, 100);
        }

        log.debug("Velocity check - user: {} (count={}), device: {} (count={}), merchant: {} (count={}), score: {}",
                userId, userCount, deviceId, deviceCount, merchantId, merchantCount, velocityScore);

        return VelocityResult.builder()
                .userCount(userCount)
                .deviceCount(deviceCount)
                .merchantCount(merchantCount)
                .userFlagged(userFlagged)
                .deviceFlagged(deviceFlagged)
                .merchantFlagged(merchantFlagged)
                .velocityScore(Math.min(velocityScore, 100))
                .build();
    }

    private int addAndCount(String key, long now, long windowStart) {
        // Remove entries older than the window
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // Add current timestamp
        redisTemplate.opsForZSet().add(key, String.valueOf(now), now);

        // Set TTL on the key to auto-clean after window + buffer
        redisTemplate.expire(key, windowSeconds + 30, TimeUnit.SECONDS);

        // Count entries in the window
        Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
        return count != null ? count.intValue() : 0;
    }

    public void removeTransaction(String key, long timestamp) {
        redisTemplate.opsForZSet().remove(key, String.valueOf(timestamp));
    }
}