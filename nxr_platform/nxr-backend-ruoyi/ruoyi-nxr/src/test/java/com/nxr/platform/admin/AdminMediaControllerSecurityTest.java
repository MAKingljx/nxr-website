package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminMediaControllerSecurityTest {

    @Test
    void singleAndBatchPublishRequireTheSamePublishPermission() {
        for (String methodName : new String[] {"publishSubmission", "publishSubmissions", "markClientPushed"}) {
            Method method = java.util.Arrays.stream(AdminMediaController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();

            assertThat(method.getAnnotation(PreAuthorize.class)).isNotNull();
            assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('nxr:media:publish')");
        }
    }

    @Test
    void folderAndEntryMediaImportsRequireTheSameImportPermission() {
        for (String methodName : new String[] {"importFolder", "importSubmissionMedia"}) {
            Method method = java.util.Arrays.stream(AdminMediaController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();

            assertThat(method.getAnnotation(PreAuthorize.class)).isNotNull();
            assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('nxr:media:import')");
        }
    }
}
