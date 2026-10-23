package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nxr.platform.commerce.OrderAccessScopeService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

class AdminSubmissionControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Security {
    }

    public static class Permissions {
        private final Set<String> permissions;

        Permissions(Set<String> permissions) {
            this.permissions = permissions;
        }

        public boolean hasPermi(String permission) {
            return permissions.contains(permission);
        }

        public boolean hasAnyPermi(String candidates) {
            return Arrays.stream(candidates.split(",")).anyMatch(this::hasPermi);
        }
    }

    @Test
    void orderGradingPermissionAllowsPreviewAndIdGenerationButNotCardDataReads() {
        AdminSubmissionService service = mock(AdminSubmissionService.class);
        withPermissions(Set.of("nxr:order:grading"), service, controller -> {
            controller.generateCertId();
            controller.calculateGrade(new AdminSubmissionController.ScorePayload(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
            verify(service).generateCertificateId();
            verify(service).calculateGrade(any());
            assertThatThrownBy(() -> controller.calculatePopulation(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.matchCard(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.listSubmissions(1, 10, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    void orderReadPermissionAloneCannotUseGradingHelpers() {
        AdminSubmissionService service = mock(AdminSubmissionService.class);
        withPermissions(Set.of("nxr:order:list"), service, controller -> {
            assertThatThrownBy(controller::generateCertId).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.calculateGrade(null)).isInstanceOf(AccessDeniedException.class);
        });
        verifyNoInteractions(service);
    }

    private void withPermissions(Set<String> permissions, AdminSubmissionService service,
                                 Consumer<AdminSubmissionController> action) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            OrderAccessScopeService accessScope = mock(OrderAccessScopeService.class);
            context.register(Security.class);
            context.registerBean("ss", Permissions.class, () -> new Permissions(permissions));
            context.registerBean(AdminSubmissionController.class, () -> new AdminSubmissionController(service, accessScope));
            context.refresh();
            action.accept(context.getBean(AdminSubmissionController.class));
            verifyNoInteractions(accessScope);
        }
    }

    @Test
    void submissionListStillRequiresReadPermission() throws NoSuchMethodException {
        Method list = AdminSubmissionController.class.getDeclaredMethod(
            "listSubmissions",
            int.class,
            int.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            java.time.LocalDate.class
        );

        assertThat(list.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("@ss.hasPermi('nxr:entry:list')");
    }

    @Test
    void statelessGradingHelpersAlsoAllowOrderGraders() {
        Set<String> helperMethods = Set.of(
            "generateCertId",
            "calculateGrade"
        );

        assertThat(AdminSubmissionController.class.getDeclaredMethods())
            .filteredOn(method -> helperMethods.contains(method.getName()))
            .hasSize(helperMethods.size())
            .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasAnyPermi('nxr:entry:list,nxr:entry:add,nxr:order:grading')"));
    }

    @Test
    void helpersReadingOtherCardsStillRequireEntryPermission() {
        Set<String> helperMethods = Set.of(
            "calculatePopulation",
            "matchCard"
        );

        assertThat(AdminSubmissionController.class.getDeclaredMethods())
            .filteredOn(method -> helperMethods.contains(method.getName()))
            .hasSize(helperMethods.size())
            .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasAnyPermi('nxr:entry:list,nxr:entry:add')"));
    }
}
