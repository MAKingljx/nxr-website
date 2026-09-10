package com.nxr.platform.payments;

import com.nxr.platform.customer.CustomerAuthService;
import com.nxr.platform.payments.PaymentModels.CheckoutRequest;
import com.nxr.platform.payments.PaymentModels.CheckoutResponse;
import com.ruoyi.common.annotation.Anonymous;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RuoYi-anonymous endpoints with explicit customer-token checks on every mutating route. */
@Anonymous
@RestController
@RequestMapping("/api/customer")
public class CustomerPaymentController {

    private static final String CUSTOMER_TOKEN_HEADER = "X-NXR-Customer-Token";

    private final CustomerAuthService customerAuthService;
    private final PaymentConfigurationService configurations;
    private final PaymentCheckoutService checkoutService;

    public CustomerPaymentController(
        CustomerAuthService customerAuthService,
        PaymentConfigurationService configurations,
        PaymentCheckoutService checkoutService
    ) {
        this.customerAuthService = customerAuthService;
        this.configurations = configurations;
        this.checkoutService = checkoutService;
    }

    @GetMapping("/payment-options")
    public List<Map<String, Object>> options(@RequestParam(required = false) String currency) {
        return configurations.listPublicOptions(currency);
    }

    @PostMapping("/orders/{orderNo}/checkout")
    public CheckoutResponse checkout(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @RequestBody CheckoutRequest request
    ) {
        long customerId = customerAuthService.requireCustomer(customerToken).id();
        return checkoutService.createCheckout(customerId, orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/checkout/paypal/{providerOrderId}/capture")
    public CheckoutResponse capturePayPal(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @PathVariable String providerOrderId
    ) {
        long customerId = customerAuthService.requireCustomer(customerToken).id();
        return checkoutService.capturePayPal(customerId, orderNo, providerOrderId);
    }
}
