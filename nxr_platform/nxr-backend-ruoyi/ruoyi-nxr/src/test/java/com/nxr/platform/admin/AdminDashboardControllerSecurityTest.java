package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.List;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminDashboardControllerSecurityTest {

    @Test
    void globalCardPermissionDoesNotExposeOtherBusinessLinesOrderAggregates() {
        var service = mock(AdminDashboardService.class);
        var scopes = mock(OrderAccessScopeService.class);
        when(scopes.scopeForUser(42L)).thenReturn(new OrderAccessScopeService.AccessScope(false,List.of(1L),List.of(1L)));
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(42L);
            security.when(() -> SecurityUtils.hasPermi(anyString())).thenReturn(true);
            new AdminDashboardController(service,scopes).dashboard();
            verify(scopes).requireUnrestrictedCardAccess(eq(42L),anyString());
            verify(service).loadDashboard(false,false,false,true);
        }
    }

    @Test
    void dashboardRequiresTheMenuPermissionGrantedToNxrRoles() throws NoSuchMethodException {
        Method dashboard = AdminDashboardController.class.getDeclaredMethod("dashboard");

        PreAuthorize guard = dashboard.getAnnotation(PreAuthorize.class);

        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("@ss.hasPermi('nxr:dashboard:view')");
    }
}
