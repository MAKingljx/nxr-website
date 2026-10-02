package com.ruoyi.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import com.ruoyi.common.core.domain.entity.SysUser;

class AuthorizationCacheServiceTest
{
    private RedisCache redis;
    private AuthorizationCacheService cache;

    @BeforeEach
    void setup()
    {
        redis = mock(RedisCache.class);
        cache = new AuthorizationCacheService();
        ReflectionTestUtils.setField(cache, "redisCache", redis);
    }

    @AfterEach
    void cleanup()
    {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void commitInvalidatesOnlyAfterDbCommitAndRollbackKeepsCurrentRevision()
    {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        cache.invalidate();
        verifyNoInteractions(redis);
        TransactionSynchronization committed = TransactionSynchronizationManager.getSynchronizations().get(0);
        committed.afterCommit();
        verify(redis).setCacheObject(eq(CacheConstants.AUTHORIZATION_REVISION_KEY), anyString());
        reset(redis);
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        cache.invalidate();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(redis);
    }

    @Test
    void noTransactionInvalidatesImmediatelyAndAbsentRedisRevisionHasStableBaseline()
    {
        assertThat(cache.getRevision()).isEqualTo("initial");
        cache.invalidate();
        verify(redis).setCacheObject(eq(CacheConstants.AUTHORIZATION_REVISION_KEY), anyString());
    }

    @Test
    void userNameValidationMatchesLoginLimitsWithoutExpandingAcceptedRange()
    {
        try (var factory = Validation.buildDefaultValidatorFactory())
        {
            Validator validator = factory.getValidator();
            assertThat(validator.validateValue(SysUser.class, "userName", "a")).isNotEmpty();
            assertThat(validator.validateValue(SysUser.class, "userName", "ab")).isEmpty();
            assertThat(validator.validateValue(SysUser.class, "userName", "a".repeat(20))).isEmpty();
            assertThat(validator.validateValue(SysUser.class, "userName", "a".repeat(21))).isNotEmpty();
        }
    }
}
