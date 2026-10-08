package com.ruoyi.framework.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.Set;
import jakarta.servlet.FilterChain;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.security.handle.AuthenticationEntryPointImpl;
import com.ruoyi.framework.web.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class JwtCredentialRevocationFilterTest
{
    private final TokenService tokens = mock(TokenService.class);
    private final JwtAuthenticationTokenFilter filter = new JwtAuthenticationTokenFilter();
    private final LoginUser login = new LoginUser(42L, null, new SysUser(42L), Set.of());
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/cards");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(filter, "tokenService", tokens);
        ReflectionTestUtils.setField(filter, "unauthorizedHandler", new AuthenticationEntryPointImpl());
        when(tokens.getLoginUser(request)).thenReturn(login);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void resetBetweenTokenReadAndRefreshUsesExistingUnauthorizedResponseAndStopsChain() throws Exception
    {
        doThrow(new ServiceException("Session expired. Please sign in again.", 401)).when(tokens).verifyToken(login);
        filter.doFilter(request, response, chain);
        assertThat(JSON.parseObject(response.getContentAsString()).getInteger("code")).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test void unrelatedServiceFailureIsNotConvertedToCredentialRevocation()
    {
        ServiceException failed = new ServiceException("database unavailable", 500);
        doThrow(failed).when(tokens).verifyToken(login);
        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failed);
        verifyNoInteractions(chain);
    }

    @Test void unrelatedRuntimeFailureStillPropagates()
    {
        IllegalStateException failed = new IllegalStateException("unexpected error");
        doThrow(failed).when(tokens).verifyToken(login);
        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failed);
        verifyNoInteractions(chain);
    }

    @Test void validTokenContinuesWithAuthenticationAndBusinessExceptionsAreNotSwallowed() throws Exception
    {
        ServiceException business = new ServiceException("business authorization", 401);
        doThrow(business).when(chain).doFilter(request, response);
        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(business);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(login);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test void absentTokenKeepsExistingAnonymousEntryPointBehavior() throws Exception
    {
        when(tokens.getLoginUser(request)).thenReturn(null);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verify(tokens, never()).verifyToken(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
