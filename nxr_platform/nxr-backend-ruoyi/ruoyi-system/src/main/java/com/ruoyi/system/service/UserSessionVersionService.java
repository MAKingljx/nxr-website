package com.ruoyi.system.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Authoritative credential epochs survive Redis eviction and concurrent stale session writes. */
@Service
public class UserSessionVersionService
{
    private final JdbcTemplate jdbc;

    public UserSessionVersionService(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    public long currentVersion(Long userId)
    {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("A backend user is required");
        List<Long> versions = jdbc.query("SELECT version FROM sys_user_session_version WHERE user_id=?",
            (row, index) -> row.getLong("version"), userId);
        if (versions.isEmpty()) return 0L;
        long version = versions.get(0);
        if (version < 0) throw new IllegalStateException("The user session version is invalid");
        return version;
    }

    /** Increment inside the same transaction as the protected account mutation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void increment(long userId)
    {
        if (userId <= 0 || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Session revocation requires an account transaction");
        jdbc.update("INSERT INTO sys_user_session_version(user_id,version,updated_at) VALUES(?,1,CURRENT_TIMESTAMP) "
            + "ON DUPLICATE KEY UPDATE version=version+1,updated_at=CURRENT_TIMESTAMP", userId);
    }
}
