package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminCardOperatorControllerTest {
    private AnnotationConfigApplicationContext context;
    private AdminCardOperatorService service;
    private AdminCardOperatorController controller;
    @Configuration @EnableMethodSecurity static class Security {
        @Bean(name = "ss") Permissions permissions() { return new Permissions(); }
    }
    public static class Permissions {
        public boolean hasPermi(String permission) {
            try { return SecurityUtils.getLoginUser().getPermissions().contains(permission); }
            catch (Exception ignored) { return false; }
        }
    }
    @BeforeEach void setup() {
        service = mock(AdminCardOperatorService.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Security.class);
        context.registerBean(AdminCardOperatorController.class, () -> new AdminCardOperatorController(service));
        context.refresh();
        controller = context.getBean(AdminCardOperatorController.class);
    }
    @AfterEach void close() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void cardUploadAndGenericUserPermissionsDoNotGrantDedicatedAccountManagement() {
        for (Set<String> permissions : List.of(Set.of("nxr:entry:list", "nxr:entry:add"), Set.of("system:user:list", "system:user:add", "system:user:resetPwd", "system:user:edit"))) {
            login(permissions);
            assertThatThrownBy(() -> controller.list(1, 20, null, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.create(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.changeStatus(101, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.resetPassword(101, null)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(service);
    }
    @Test void eachActionRequiresItsExactDedicatedPermissionAndCarriesAuthenticatedActor() {
        login(Set.of("nxr:card-user:list"));
        controller.list(2, 10, "upload", "0");
        verify(service).list(50, 2, 10, "upload", "0");
        assertThatThrownBy(() -> controller.create(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.changeStatus(101, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.resetPassword(101, null)).isInstanceOf(AccessDeniedException.class);
        login(Set.of("nxr:card-user:add"));
        var create = new AdminCardOperatorService.CreateRequest("upload_1", "Uploader", "UploadPass1");
        controller.create(create); verify(service).create(50, create);
        login(Set.of("nxr:card-user:edit"));
        var status = new AdminCardOperatorService.StatusRequest("1");
        controller.changeStatus(101, status); verify(service).changeStatus(50, 101, status);
        login(Set.of("nxr:card-user:resetPwd"));
        var password = new AdminCardOperatorService.PasswordRequest("UploadPass2");
        controller.resetPassword(101, password); verify(service).resetPassword(50, 101, password);
    }
    @Test void credentialsAndResponsesAreExcludedFromOperationAudit() throws Exception {
        for (String name : List.of("create", "resetPassword", "changeStatus")) {
            var method = java.util.Arrays.stream(AdminCardOperatorController.class.getDeclaredMethods()).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
            Log annotation = method.getAnnotation(Log.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.isSaveRequestData()).isFalse();
            assertThat(annotation.isSaveResponseData()).isFalse();
        }
        assertThat(java.util.Arrays.stream(AdminCardOperatorController.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
            .doesNotContain("assignRole", "grantPermissions", "delete", "listRoles", "finance");
    }
    private void login(Set<String> permissions) {
        SysUser user = new SysUser(); user.setUserId(50L); user.setUserName("nxr_card_super_01");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(50L, null, user, permissions), "unused", List.of()));
    }
}
