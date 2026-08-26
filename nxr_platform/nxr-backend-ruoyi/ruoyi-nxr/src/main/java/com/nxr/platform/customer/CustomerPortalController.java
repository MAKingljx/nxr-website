package com.nxr.platform.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final OrderFulfillmentService orderFulfillmentService;
    private final MerchantBulkOrderService merchantBulkOrderService;

    @Value("${nxr.payments.callback-token:}")
    private String paymentCallbackToken;

    public CustomerPortalController(
        CustomerAuthService customerAuthService,
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService,
        MerchantBulkOrderService merchantBulkOrderService
    ) {
        this.customerAuthService = customerAuthService;
        this.customerPortalService = customerPortalService;
        this.orderFulfillmentService = orderFulfillmentService;
        this.merchantBulkOrderService = merchantBulkOrderService;
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

    @GetMapping("/addresses")
    public java.util.List<OrderFulfillmentService.CustomerAddress> addresses(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken
    ) {
        return orderFulfillmentService.listAddresses(current(customerToken).id());
    }

    @PostMapping("/addresses")
    public OrderFulfillmentService.CustomerAddress createAddress(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @RequestBody OrderFulfillmentService.AddressRequest request
    ) {
        return orderFulfillmentService.saveAddress(current(customerToken).id(), null, request);
    }

    @PutMapping("/addresses/{addressId}")
    public OrderFulfillmentService.CustomerAddress updateAddress(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable long addressId,
        @RequestBody OrderFulfillmentService.AddressRequest request
    ) {
        return orderFulfillmentService.saveAddress(current(customerToken).id(), addressId, request);
    }

    @DeleteMapping("/addresses/{addressId}")
    public Map<String, Boolean> deleteAddress(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable long addressId
    ) {
        orderFulfillmentService.deleteAddress(current(customerToken).id(), addressId);
        return Map.of("success", true);
    }

    @GetMapping("/shipping-options")
    public java.util.List<OrderFulfillmentService.ShippingOption> shippingOptions(
        @RequestParam(required = false) String country
    ) {
        return orderFulfillmentService.listShippingOptions(country, false);
    }

    @GetMapping("/service-price")
    public OrderFulfillmentService.ServicePrice servicePrice() {
        return orderFulfillmentService.activeServicePrice();
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

    @PostMapping("/orders/{orderNo}/payment-session")
    public CustomerPortalService.PaymentSessionResponse createPaymentSession(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @RequestBody CustomerPortalService.PaymentSessionRequest request
    ) {
        return customerPortalService.createPaymentSession(current(customerToken).id(), orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/inbound-shipment")
    public CustomerPortalService.OrderDetailResponse addInboundShipment(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @RequestBody CustomerPortalService.CreateShipmentRequest request
    ) {
        return customerPortalService.addInboundShipment(current(customerToken).id(), orderNo, request);
    }

    @GetMapping("/orders/{orderNo}/packing-slip")
    public OrderFulfillmentService.PackingSlip packingSlip(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo
    ) {
        return orderFulfillmentService.requirePackingSlip(current(customerToken).id(), orderNo);
    }

    @GetMapping("/orders/{orderNo}/operations")
    public OrderFulfillmentService.CustomerOperationsResponse orderOperations(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo
    ) {
        return orderFulfillmentService.loadCustomerOperations(current(customerToken).id(), orderNo);
    }

    @PostMapping("/orders/{orderNo}/tickets")
    public OrderFulfillmentService.TicketRecord createTicket(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @RequestBody OrderFulfillmentService.TicketRequest request
    ) {
        return orderFulfillmentService.createTicket(current(customerToken).id(), orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/tickets/{ticketId}/messages")
    public OrderFulfillmentService.TicketRecord addTicketMessage(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @PathVariable long ticketId,
        @RequestBody OrderFulfillmentService.TicketMessageRequest request
    ) {
        long customerId = current(customerToken).id();
        boolean ticketBelongsToOrder = orderFulfillmentService.listCustomerTickets(customerId, orderNo).stream()
            .anyMatch(ticket -> ticket.id() == ticketId);
        if (!ticketBelongsToOrder) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Support ticket not found");
        }
        return orderFulfillmentService.addCustomerTicketMessage(customerId, ticketId, request);
    }

    @PostMapping("/orders/{orderNo}/shipping-change")
    public OrderFulfillmentService.ShippingChangeRecord requestShippingChange(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String orderNo,
        @RequestBody OrderFulfillmentService.ShippingChangeRequest request
    ) {
        return orderFulfillmentService.requestShippingChange(current(customerToken).id(), orderNo, request);
    }

    @GetMapping(value = "/merchant/orders/template", produces = "text/csv;charset=UTF-8")
    public String merchantOrderTemplate(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken
    ) {
        orderFulfillmentService.requireMerchant(current(customerToken).id());
        return "language_code,quantity,return_address_id,return_shipping_option_code,customer_note\n"
            + "EN,1,1,economy_line,Example merchant order\n";
    }

    @PostMapping("/merchant/orders/bulk")
    public OrderFulfillmentService.MerchantImportResult createMerchantOrders(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @RequestBody MerchantBulkOrderService.BulkOrderRequest request
    ) {
        return merchantBulkOrderService.createOrders(current(customerToken).id(), request);
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
