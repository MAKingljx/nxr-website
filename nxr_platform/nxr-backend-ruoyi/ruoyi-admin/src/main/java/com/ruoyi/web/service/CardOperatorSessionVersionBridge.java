package com.ruoyi.web.service;

import com.nxr.platform.admin.CardOperatorSessionInvalidator;
import com.ruoyi.system.service.AuthorizationCacheService;
import com.ruoyi.system.service.UserSessionVersionService;
import org.springframework.stereotype.Component;

/** Connect the card business to runtime sessions without giving it platform-user privileges. */
@Component
public class CardOperatorSessionVersionBridge implements CardOperatorSessionInvalidator
{
    private final UserSessionVersionService versions;
    private final AuthorizationCacheService authorization;

    public CardOperatorSessionVersionBridge(UserSessionVersionService versions, AuthorizationCacheService authorization)
    {
        this.versions = versions;
        this.authorization = authorization;
    }

    @Override
    public void revokeAfterCommit(long userId)
    {
        // The epoch changes on DB commit and rolls back with the password/status update.
        // The existing interface name describes when revocation becomes externally visible.
        versions.increment(userId);
        authorization.invalidate();
    }
}
