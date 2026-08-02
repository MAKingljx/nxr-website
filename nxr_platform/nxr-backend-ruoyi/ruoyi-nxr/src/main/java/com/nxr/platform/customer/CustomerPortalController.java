package com.nxr.platform.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.ruoyi.common.annotation.Anonymous;

/** Anonymous to RuoYi because customer authorization is resolved from its own session header. */
@Anonymous
@RestController
@RequestMapping("/api/customer")
public class CustomerPortalController {

    private static final String CUSTOMER_TOKEN_HEADER = "X-NXR-Customer-Token";

    private final CustomerAuthService customerAuthService;
    private final CustomerPortalService customerPortalService;

    @Value("${nxr.payments.callback-token:}")
    private String paymentCallbackToken;

    public CustomerPortalController(CustomerAuthService customerAuthService, CustomerPortalService customerPortalService) {
        this.customerAuthService = customerAuthService;
        this.customerPortalService = customerPortalService;
    }

    @PostMapping("/auth/register")
    public CustomerAuthService.CustomerAuthResponse register(@RequestBody CustomerAuthService.RegisterRequest request) {
        return customerAuthService.register(request);
    }

    @PostMapping("/auth/login")
    public CustomerAuthService.CustomerAuthResponse login(@RequestBody CustomerAuthService.LoginRequest request) {
        return customerAuthService.login(request);
    }

    @PostMapping("/auth/logout")
    public Map<String, Boolean> logout(@RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken) {
        customerAuthService.logout(customerToken);
        return Map.of("success", true);
    }

    @GetMapping("/auth/me")
    public CustomerAuthService.CustomerProfile currentCustomer(@RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken) {
        return customerAuthService.profile(customerAuthService.requireCustomer(customerToken));
    }

    @GetMapping("/cards/{certId}/community")
    public CustomerPortalService.CardCommunityResponse cardCommunity(@PathVariable String certId) {
        return customerPortalService.loadCardCommunity(certId);
    }

    @GetMapping("/cards")
    public java.util.List<CustomerPortalService.CustomerCardResponse> customerCards(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken
    ) {
        return customerPortalService.listCustomerCards(current(customerToken).id());
    }

    @PostMapping("/cards/{certId}/claim")
    public CustomerPortalService.CardCommunityResponse claimCard(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String certId,
        @RequestBody CustomerPortalService.ClaimCardRequest request
    ) {
        return customerPortalService.claimCard(current(customerToken).id(), certId, request);
    }

    @PostMapping("/cards/{certId}/transfer")
    public CustomerPortalService.CardCommunityResponse transferCard(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String certId,
        @RequestBody CustomerPortalService.TransferCardRequest request
    ) {
        return customerPortalService.transferCard(current(customerToken).id(), certId, request, customerAuthService);
    }

    @PostMapping("/orders")
    public CustomerPortalService.OrderDetailResponse createOrder(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @RequestBody CustomerPortalService.CreateOrderRequest request
    ) {
        return customerPortalService.createOrder(current(customerToken).id(), request);
    }

    @GetMapping("/orders")
    public CustomerPortalService.OrderListResponse listOrders(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return customerPortalService.listCustomerOrders(current(customerToken).id(), page, pageSize);
    }

    @GetMapping("/orders/{orderNo}")
    public CustomerPortalService.OrderDetailResponse orderDetail(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo
    ) {
        return customerPortalService.requireCustomerOrder(current(customerToken).id(), orderNo);
    }

    @PostMapping("/orders/{orderNo}/payment-proof")
    public CustomerPortalService.OrderDetailResponse submitPaymentProof(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @RequestBody CustomerPortalService.SubmitPaymentProofRequest request
    ) {
        return customerPortalService.submitPaymentProof(current(customerToken).id(), orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/inbound-shipment")
    public CustomerPortalService.OrderDetailResponse addInboundShipment(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @RequestBody CustomerPortalService.CreateShipmentRequest request
    ) {
        return customerPortalService.addInboundShipment(current(customerToken).id(), orderNo, request);
    }

    @PostMapping("/payments/callback/{provider}")
    public CustomerPortalService.PaymentCallbackResponse paymentCallback(
        @RequestHeader(name = "X-NXR-Payment-Callback-Token", required = false) String callbackToken,
        @PathVariable String provider,
        @RequestBody CustomerPortalService.PaymentCallbackRequest request
    ) {
        if (paymentCallbackToken == null || paymentCallbackToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment callback is not configured");
        }
        if (callbackToken == null || !MessageDigest.isEqual(
            paymentCallbackToken.getBytes(StandardCharsets.UTF_8), callbackToken.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid payment callback token");
        }
        return customerPortalService.receivePaymentCallback(provider, request);
    }

    private CustomerAuthService.CustomerAccount current(String customerToken) {
        return customerAuthService.requireCustomer(customerToken);
    }
}
