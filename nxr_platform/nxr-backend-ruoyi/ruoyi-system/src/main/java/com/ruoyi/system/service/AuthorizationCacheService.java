package com.ruoyi.system.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.uuid.IdUtils;

/**
 * Invalidates authorization snapshots across all application instances without scanning sessions.
 * The next authenticated request reloads current user, roles and permissions while keeping its TTL.
 */
@Service
public class AuthorizationCacheService
{
    @Autowired
    private RedisCache redisCache;

    public String getRevision()
    {
        String revision = redisCache.getCacheObject(CacheConstants.AUTHORIZATION_REVISION_KEY);
        return revision == null ? "initial" : revision;
    }

    public void invalidate()
    {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive())
        {
            // Invalidating before commit could cache the old DB state under the new revision.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    changeRevision();
                }
            });
        }
        else
        {
            changeRevision();
        }
    }

    private void changeRevision()
    {
        redisCache.setCacheObject(CacheConstants.AUTHORIZATION_REVISION_KEY, IdUtils.fastUUID());
    }
}
