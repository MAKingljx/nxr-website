package com.nxr.platform.commerce;

import com.nxr.platform.customer.CustomerAuthService;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated customer quote preview. The requested customer id can never select another account's price. */
@Anonymous
@RestController
@RequestMapping("/api/customer/commerce")
public class CustomerCommercePolicyController {

    private final CustomerAuthService customerAuthService;
    private final CommercePolicyService policyService;

    public CustomerCommercePolicyController(CustomerAuthService customerAuthService, CommercePolicyService policyService) {
        this.customerAuthService = customerAuthService;
        this.policyService = policyService;
    }

    @GetMapping("/quote-preview")
    public CommercePolicyService.QuoteResult quotePreview(
        @RequestHeader(name = "X-NXR-Customer-Token", required = false) String customerToken,
        @RequestParam long customerId,
        @RequestParam String country,
        @RequestParam String currency,
        @RequestParam int count,
        @RequestParam(required = false) String shippingOptionCode
    ) {
        long authenticatedCustomerId = customerAuthService.requireCustomer(customerToken).id();
        if (authenticatedCustomerId != customerId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Quote access is not allowed");
        }
        return policyService.quoteForOrder(customerId, country, currency, count, shippingOptionCode);
    }

    @PostMapping("/batch-quote-preview")
    public CommercePolicyService.BatchQuoteResult batchQuotePreview(
        @RequestHeader(name = "X-NXR-Customer-Token", required = false) String customerToken,
        @RequestBody BatchQuoteRequest request
    ) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch quote is required");
        long authenticatedCustomerId = customerAuthService.requireCustomer(customerToken).id();
        if (authenticatedCustomerId != request.customerId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Quote access is not allowed");
        }
        return policyService.quoteBatch(request.customerId(), request.country(), request.currency(), request.parts(),
            request.shippingOptionCode());
    }

    public record BatchQuoteRequest(long customerId, String country, String currency,
                                    String shippingOptionCode,
                                    java.util.List<CommercePolicyService.BatchPartRequest> parts) {}
}
