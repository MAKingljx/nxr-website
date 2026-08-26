package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminBrandSettingsControllerSecurityTest {

    @Test
    void maintenanceListKeepsTheBrandSettingsPermission() throws NoSuchMethodException {
        Method listBrands = AdminBrandSettingsController.class.getDeclaredMethod("listBrands");

        PreAuthorize guard = listBrands.getAnnotation(PreAuthorize.class);

        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("@ss.hasPermi('nxr:brand:list')");
    }

    @Test
    void entryFormOptionsAllowEntryReadersAndCreators() throws NoSuchMethodException {
        Method listOptions = AdminBrandSettingsController.class.getDeclaredMethod("listActiveBrandOptions");

        PreAuthorize guard = listOptions.getAnnotation(PreAuthorize.class);

        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("@ss.hasAnyPermi('nxr:entry:list,nxr:entry:add')");
    }
}
