package com.ruoyi.framework.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Set;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.UserSessionVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.framework.security.context.AuthenticationContextHolder;

class SysLoginSessionVersionTest
{
    @AfterEach void cleanup() { AuthenticationContextHolder.clearContext(); }

    @Test void resetDuringPasswordAuthenticationCannotMintANewEpochToken()
    {
        ISysUserService users = mock(ISysUserService.class);
        UserSessionVersionService versions = mock(UserSessionVersionService.class);
        TokenService tokens = mock(TokenService.class);
        AuthenticationManager authentication = mock(AuthenticationManager.class);
        SysUser user = new SysUser(42L); user.setUserName("card_upload_01");
        LoginUser principal = new LoginUser(42L, null, user, Set.of("nxr:entry:add"));
        when(users.selectUserByUserName(user.getUserName())).thenReturn(user);
        when(versions.currentVersion(42L)).thenReturn(0L);
        when(authentication.authenticate(any())).thenAnswer(call -> {
            // The version was captured before authentication; a reset now commits.
            verify(versions).currentVersion(42L);
            when(versions.currentVersion(42L)).thenReturn(1L);
            return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        });
        SysLoginService service = service(users, versions, tokens, authentication);
        assertThatThrownBy(() -> service.login(user.getUserName(), "OldPassword", "", "", true))
            .isInstanceOf(ServiceException.class)
            .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(401));
        verifyNoInteractions(tokens);
        assertThat(AuthenticationContextHolder.getContext()).isNull();
    }

    @Test void accountIdentityChangingDuringAuthenticationCannotReuseTheCapturedVersion()
    {
        ISysUserService users = mock(ISysUserService.class);
        UserSessionVersionService versions = mock(UserSessionVersionService.class);
        TokenService tokens = mock(TokenService.class);
        AuthenticationManager authentication = mock(AuthenticationManager.class);
        SysUser original = new SysUser(42L); original.setUserName("card_upload_01");
        SysUser replacement = new SysUser(43L); replacement.setUserName(original.getUserName());
        when(users.selectUserByUserName(original.getUserName())).thenReturn(original);
        when(versions.currentVersion(42L)).thenReturn(0L);
        LoginUser principal = new LoginUser(43L, null, replacement, Set.of());
        when(authentication.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        assertThatThrownBy(() -> service(users, versions, tokens, authentication)
            .login(original.getUserName(), "OldPassword", "", ""))
            .isInstanceOf(ServiceException.class);
        verifyNoInteractions(tokens);
    }

    @Test void freshLoginAfterResetPassesItsCapturedEpochAndRememberPreferenceToTokenCreation()
    {
        ISysUserService users = mock(ISysUserService.class);
        UserSessionVersionService versions = mock(UserSessionVersionService.class);
        TokenService tokens = mock(TokenService.class);
        AuthenticationManager authentication = mock(AuthenticationManager.class);
        SysUser user = new SysUser(42L); user.setUserName("card_upload_01");
        LoginUser principal = new LoginUser(42L, null, user, Set.of());
        when(users.selectUserByUserName(user.getUserName())).thenReturn(user);
        when(versions.currentVersion(42L)).thenReturn(1L);
        when(authentication.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(tokens.createToken(principal, true, 1L)).thenReturn("issued-token");
        // Isolate unrelated asynchronous audit infrastructure; do not execute background jobs.
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class);
             MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class);
             MockedStatic<AsyncFactory> factory = mockStatic(AsyncFactory.class))
        {
            spring.when(() -> SpringUtils.getBean("scheduledExecutorService")).thenReturn(mock(ScheduledExecutorService.class));
            messages.when(() -> MessageUtils.message("user.login.success")).thenReturn("ok");
            try (MockedStatic<AsyncManager> async = mockStatic(AsyncManager.class))
            {
                async.when(AsyncManager::me).thenReturn(mock(AsyncManager.class));
                assertThat(service(users, versions, tokens, authentication)
                    .login(user.getUserName(), "NewPassword", "", "", true)).isEqualTo("issued-token");
            }
        }
        var flow = inOrder(versions, authentication, tokens);
        flow.verify(versions).currentVersion(42L);
        flow.verify(authentication).authenticate(any());
        flow.verify(versions).currentVersion(42L);
        flow.verify(tokens).createToken(principal, true, 1L);
    }

    private SysLoginService service(ISysUserService users, UserSessionVersionService versions,
                                    TokenService tokens, AuthenticationManager authentication)
    {
        SysLoginService service = new SysLoginService()
        {
            @Override public void validateCaptcha(String username, String code, String uuid) { }
            @Override public void loginPreCheck(String username, String password) { }
            @Override public void recordLoginInfo(Long userId) { }
        };
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "sessionVersions", versions);
        ReflectionTestUtils.setField(service, "tokenService", tokens);
        ReflectionTestUtils.setField(service, "authenticationManager", authentication);
        return service;
    }
}
