package com.nxr.platform.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WaitlistConfirmationServiceTest {

    @Test
    void noProviderConfigurationDoesNotClaimThatEmailWasQueued() {
        WaitlistConfirmationService service = new WaitlistConfirmationService(
            new ObjectMapper(),
            Runnable::run,
            "",
            "",
            "",
            587,
            "",
            "",
            true
        );

        assertThat(service.queueConfirmation("collector@example.com")).isFalse();
    }
}
