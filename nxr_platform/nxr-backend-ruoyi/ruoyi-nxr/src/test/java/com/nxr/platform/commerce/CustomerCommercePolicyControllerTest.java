package com.nxr.platform.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nxr.platform.customer.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CustomerCommercePolicyControllerTest {

    @Test
    void rejectsQuoteForAnotherCustomerBeforePolicyLookup() {
        CustomerAuthService auth = mock(CustomerAuthService.class);
        CommercePolicyService policy = mock(CommercePolicyService.class);
        when(auth.requireCustomer("session-token")).thenReturn(new CustomerAuthService.CustomerAccount(
            41L, "owner@example.test", "hash", "Owner", null, "collector", true, null, null
        ));
        CustomerCommercePolicyController controller = new CustomerCommercePolicyController(auth, policy);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.quotePreview("session-token", 42L, "US", "USD", 3, null));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(policy);
    }
}
