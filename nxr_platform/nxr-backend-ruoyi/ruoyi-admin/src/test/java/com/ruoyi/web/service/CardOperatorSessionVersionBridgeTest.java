package com.ruoyi.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.system.service.AuthorizationCacheService;
import com.ruoyi.system.service.UserSessionVersionService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class CardOperatorSessionVersionBridgeTest
{
    private JdbcTemplate jdbc;
    private UserSessionVersionService versions;
    private CardOperatorSessionVersionBridge bridge;
    private TransactionTemplate transaction;
    private Map<String, Object> redis;

    @BeforeEach void setup()
    {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:card_session_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,password VARCHAR(100),status VARCHAR(1))");
        jdbc.execute("CREATE TABLE sys_user_session_version(user_id BIGINT PRIMARY KEY,version BIGINT NOT NULL DEFAULT 0,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(user_id) REFERENCES sys_user(user_id))");
        jdbc.update("INSERT INTO sys_user VALUES(42,'original','0'),(43,'other','0')");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        versions = new UserSessionVersionService(jdbc);
        redis = new HashMap<>();
        RedisCache cache = new RedisCache()
        {
            @Override @SuppressWarnings("unchecked") public <T> T getCacheObject(String key) { return (T) redis.get(key); }
            @Override public <T> void setCacheObject(String key, T value) { redis.put(key, value); }
        };
        AuthorizationCacheService authorization = new AuthorizationCacheService();
        ReflectionTestUtils.setField(authorization, "redisCache", cache);
        bridge = new CardOperatorSessionVersionBridge(versions, authorization);
    }

    @Test void passwordStatusAndEpochCommitTogetherWhileOnlyAuthorizationCacheWaitsForCommit()
    {
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE sys_user SET password='replacement',status='1' WHERE user_id=42");
            bridge.revokeAfterCommit(42);
            assertThat(versions.currentVersion(42L)).isEqualTo(1);
            assertThat(redis).isEmpty();
        });
        assertThat(versions.currentVersion(42L)).isEqualTo(1);
        assertThat(versions.currentVersion(43L)).isZero();
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=42", String.class)).isEqualTo("replacement");
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=43", String.class)).isEqualTo("other");
        assertThat(redis).containsKey(CacheConstants.AUTHORIZATION_REVISION_KEY);
        transaction.executeWithoutResult(status -> bridge.revokeAfterCommit(42));
        assertThat(versions.currentVersion(42L)).isEqualTo(2);
    }

    @Test void failedAccountMutationRollsBackEpochAndDoesNotInvalidateAnySessionSnapshot()
    {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE sys_user SET password='replacement',status='1' WHERE user_id=42");
            bridge.revokeAfterCommit(42);
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(versions.currentVersion(42L)).isZero();
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=42", String.class)).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE user_id=42", String.class)).isEqualTo("0");
        assertThat(redis).isEmpty();
    }

    @Test void bridgeCannotRevokeSessionsOutsideAnAccountTransaction()
    {
        assertThatThrownBy(() -> bridge.revokeAfterCommit(42)).isInstanceOf(IllegalStateException.class);
        assertThat(versions.currentVersion(42L)).isZero();
        assertThat(redis).isEmpty();
    }
}
