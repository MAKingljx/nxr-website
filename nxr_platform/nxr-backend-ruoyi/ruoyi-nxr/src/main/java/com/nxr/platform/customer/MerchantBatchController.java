package com.nxr.platform.customer;

import com.ruoyi.common.annotation.Anonymous;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant-owned master batches. Customer authentication stays in the private session header. */
@Anonymous
@RestController
@RequestMapping("/api/customer/merchant/batches")
public class MerchantBatchController {

    private static final String CUSTOMER_TOKEN_HEADER = "X-NXR-Customer-Token";

    private final CustomerAuthService customerAuthService;
    private final MerchantBatchService merchantBatchService;

    public MerchantBatchController(CustomerAuthService customerAuthService, MerchantBatchService merchantBatchService) {
        this.customerAuthService = customerAuthService;
        this.merchantBatchService = merchantBatchService;
    }

    @PostMapping
    public MerchantBatchService.BatchCreateResult create(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @RequestBody MerchantBatchService.BatchCreateRequest request
    ) {
        return merchantBatchService.createBatch(current(customerToken).id(), request);
    }

    @GetMapping
    public MerchantBatchService.BatchPage list(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return merchantBatchService.listMerchantBatches(current(customerToken).id(), page, pageSize);
    }

    @GetMapping("/{batchNo}")
    public MerchantBatchService.BatchDetail detail(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String batchNo
    ) {
        return merchantBatchService.requireMerchantBatch(current(customerToken).id(), batchNo);
    }

    @PostMapping("/{batchNo}/orders/{orderNo}/tracking-token/rotate")
    public MerchantBatchService.TrackingTokenResponse rotateToken(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String batchNo,
        @PathVariable String orderNo
    ) {
        return merchantBatchService.rotateTrackingToken(current(customerToken).id(), batchNo, orderNo);
    }

    @DeleteMapping("/{batchNo}/orders/{orderNo}/tracking-token")
    public Map<String, Boolean> revokeToken(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String batchNo,
        @PathVariable String orderNo
    ) {
        merchantBatchService.revokeTrackingToken(current(customerToken).id(), batchNo, orderNo);
        return Map.of("revoked", true);
    }

    /** A merchant can declare only the inbound master parcel; return shipping is staff-only. */
    @PostMapping("/{batchNo}/inbound-shipment")
    public MerchantBatchService.BatchDetail inboundShipment(
        @RequestHeader(name = CUSTOMER_TOKEN_HEADER, required = false) String customerToken,
        @PathVariable String batchNo,
        @RequestBody MerchantBatchService.BatchShipmentRequest request
    ) {
        return merchantBatchService.createInboundShipment(current(customerToken).id(), batchNo, request);
    }

    private CustomerAuthService.CustomerAccount current(String token) {
        return customerAuthService.requireCustomer(token);
    }
}
