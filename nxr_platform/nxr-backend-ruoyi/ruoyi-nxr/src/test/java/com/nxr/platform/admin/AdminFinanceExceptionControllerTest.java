package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.payments.FinanceExceptionReviewService;
import com.nxr.platform.payments.FinanceExceptionReviewService.ReviewContext;
import com.nxr.platform.payments.FinanceExceptionReviewService.ReviewRequest;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.BusinessType;

class AdminFinanceExceptionControllerTest {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"list", "context", "review"})
    void inaccessibleOrderIsRejectedBeforeAnyFinanceServiceReadOrWrite(String action) {
        var service = mock(FinanceExceptionReviewService.class);
        var scope = mock(OrderAccessScopeService.class);
        var controller = new AdminFinanceExceptionController(service, scope);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not accessible")).when(scope).requireAccessibleOrder(22L);
        assertThatThrownBy(() -> {
            switch (action) {
                case "list" -> controller.list(22L);
                case "context" -> controller.context(22L);
                default -> controller.review(22L, request());
            }
        }).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(scope).requireAccessibleOrder(22L);
        verifyNoInteractions(service);
    }

    @Test void reviewUsesAuthenticatedActorAndTheScopedOrderWithoutChangingTheRequest() {
        var service = mock(FinanceExceptionReviewService.class);
        var scope = mock(OrderAccessScopeService.class);
        var controller = new AdminFinanceExceptionController(service, scope);
        LoginUser actor = new LoginUser(91L, 10L, new SysUser(91L), java.util.Set.of("nxr:customer:finance"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null));
        ReviewRequest request = request();
        ReviewContext result = new ReviewContext(22L, "received", null, new BigDecimal("100.00"), "USD", List.of(), false, false, true, "Resolved", "Resolved");
        when(service.review(22L, 91L, request)).thenReturn(result);
        assertThat(controller.review(22L, request).get("data")).isSameAs(result);
        var calls = inOrder(scope, service);
        calls.verify(scope).requireAccessibleOrder(22L);
        calls.verify(service).review(22L, 91L, request);
    }

    @Test void listsAndContextAlsoKeepTheOrderScopeBoundary() {
        var service = mock(FinanceExceptionReviewService.class);
        var scope = mock(OrderAccessScopeService.class);
        var controller = new AdminFinanceExceptionController(service, scope);
        when(service.listForOrder(22L)).thenReturn(List.of());
        controller.list(22L);
        controller.context(22L);
        var calls = inOrder(scope, service);
        calls.verify(scope).requireAccessibleOrder(22L);
        calls.verify(service).listForOrder(22L);
        calls.verify(scope).requireAccessibleOrder(22L);
        calls.verify(service).reviewContext(22L);
    }

    @Test void allEndpointsRequirePaymentOrCustomerFinancePermission() {
        PreAuthorize permission = AdminFinanceExceptionController.class.getAnnotation(PreAuthorize.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo("@ss.hasAnyPermi('nxr:order:payment,nxr:customer:finance')");
    }

    @Test void reviewIsAuditedWithoutLoggingFinancialEvidencePayloadOrResponse() throws Exception {
        Log audit = AdminFinanceExceptionController.class.getMethod("review", long.class, ReviewRequest.class).getAnnotation(Log.class);
        assertThat(audit).isNotNull();
        assertThat(audit.businessType()).isEqualTo(BusinessType.UPDATE);
        assertThat(audit.isSaveRequestData()).isFalse();
        assertThat(audit.isSaveResponseData()).isFalse();
    }

    private static ReviewRequest request() {
        return new ReviewRequest("restore", List.of(12L), "PRIVATE-BANK-EVIDENCE", "Funds verified", new BigDecimal("100.00"), "USD");
    }
}
