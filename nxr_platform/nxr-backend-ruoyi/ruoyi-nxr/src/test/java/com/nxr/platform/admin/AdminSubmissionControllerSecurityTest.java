package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminSubmissionControllerSecurityTest {

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
            String.class
        );

        assertThat(list.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("@ss.hasPermi('nxr:entry:list')");
    }

    @Test
    void createHelpersAllowEntryReadersOrCreators() {
        Set<String> helperMethods = Set.of(
            "generateCertId",
            "calculateGrade",
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
