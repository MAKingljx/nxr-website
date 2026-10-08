package com.nxr.platform.admin;

/** Runtime bridge: revoke only this backend user's existing sessions after the transaction commits. */
@FunctionalInterface
public interface CardOperatorSessionInvalidator {
    void revokeAfterCommit(long userId);
}
