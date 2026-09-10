package com.nxr.platform.customer;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, bearer-link view scoped to exactly one merchant child order. */
@Anonymous
@RestController
@RequestMapping("/api/public/merchant-order-tracking")
public class MerchantBatchPublicController {

    private final MerchantBatchService merchantBatchService;

    public MerchantBatchPublicController(MerchantBatchService merchantBatchService) {
        this.merchantBatchService = merchantBatchService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<MerchantBatchService.PublicTrackingResponse> tracking(@PathVariable String token) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            .body(merchantBatchService.publicTracking(token));
    }
}
