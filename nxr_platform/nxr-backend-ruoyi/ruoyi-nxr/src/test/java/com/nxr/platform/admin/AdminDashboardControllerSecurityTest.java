package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminDashboardControllerSecurityTest {

    @Test
    void dashboardRequiresTheMenuPermissionGrantedToNxrRoles() throws NoSuchMethodException {
        Method dashboard = AdminDashboardController.class.getDeclaredMethod("dashboard");

        PreAuthorize guard = dashboard.getAnnotation(PreAuthorize.class);

        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("@ss.hasPermi('nxr:dashboard:view')");
    }
}
