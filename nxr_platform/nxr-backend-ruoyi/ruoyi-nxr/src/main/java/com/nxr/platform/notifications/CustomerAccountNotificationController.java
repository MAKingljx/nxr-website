package com.nxr.platform.notifications;

import com.nxr.platform.customer.CustomerAuthService;
import com.ruoyi.common.annotation.Anonymous;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer auth remains independent from RuoYi staff authentication. */
@Anonymous
@RestController
@RequestMapping("/api/customer/account")
public class CustomerAccountNotificationController {

    private static final String CUSTOMER_TOKEN_HEADER = "X-NXR-Customer-Token";
    private final CustomerAuthService customerAuthService;
    private final CustomerAccountNotificationService notificationService;
    private final NotificationOutboxService outboxService;

    public CustomerAccountNotificationController(
        CustomerAuthService customerAuthService,
        CustomerAccountNotificationService notificationService,
        NotificationOutboxService outboxService
    ) {
        this.customerAuthService = customerAuthService;
        this.notificationService = notificationService;
        this.outboxService = outboxService;
    }

    @GetMapping("/email-status")
    public AccountEmailStatus emailStatus(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken
    ) {
        if (customerToken == null || customerToken.isBlank()) {
            return new AccountEmailStatus(null, null, outboxService.deliveryCapability());
        }
        CustomerAccountNotificationService.EmailStatus status = notificationService.emailStatus(
            customerAuthService.requireCustomer(customerToken)
        );
        return new AccountEmailStatus(status.email(), status.verified(), status.delivery());
    }

    @PostMapping("/email-verification/request")
    public CustomerAccountNotificationService.VerificationRequestResult requestEmailVerification(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        HttpServletRequest request
    ) {
        return notificationService.requestEmailVerification(
            customerAuthService.requireCustomer(customerToken), request.getRemoteAddr()
        );
    }

    @PostMapping("/email-verification/confirm")
    public CustomerAccountNotificationService.ConfirmResult confirmEmailVerification(
        @RequestBody TokenRequest request
    ) {
        return notificationService.confirmEmailVerification(request == null ? null : request.token());
    }

    @PostMapping("/password-reset/request")
    public CustomerAccountNotificationService.ResetRequestResult requestPasswordReset(
        @RequestBody EmailRequest request,
        HttpServletRequest servletRequest
    ) {
        return notificationService.requestPasswordReset(
            request == null ? null : request.email(), servletRequest.getRemoteAddr()
        );
    }

    @PostMapping("/password-reset/confirm")
    public CustomerAccountNotificationService.ConfirmResult confirmPasswordReset(
        @RequestBody PasswordResetConfirmRequest request
    ) {
        return notificationService.confirmPasswordReset(
            request == null ? null : request.token(), request == null ? null : request.password()
        );
    }

    public record AccountEmailStatus(
        String email,
        Boolean verified,
        NotificationOutboxService.DeliveryCapability delivery
    ) {
    }

    public record TokenRequest(String token) {
    }

    public record EmailRequest(String email) {
    }

    public record PasswordResetConfirmRequest(String token, String password) {
    }
}
