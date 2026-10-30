package com.nxr.platform.customer;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

/** Read-only, bearer-link view scoped to exactly one merchant child order. */
@Anonymous
@RestController
@RequestMapping("/api/public/merchant-order-tracking")
public class MerchantBatchPublicController {

    private final MerchantBatchService merchantBatchService;
    @Autowired(required=false) private AgentOrderTrackingService orderTracking;
    @Autowired(required=false) private TrackingAccessService trackingAccess;
    @Autowired(required=false) private TrackingClientAddressResolver clientAddress;

    public MerchantBatchPublicController(MerchantBatchService merchantBatchService) {
        this.merchantBatchService = merchantBatchService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<MerchantBatchService.PublicTrackingResponse> tracking(@PathVariable String token,
        @RequestHeader(value="X-NXR-Tracking-Access",required=false) String access) {
        requireAccess(token,access);
        var body=orderTracking==null ? merchantBatchService.publicTracking(token) : orderTracking.publicView(token)
            .orElseGet(() -> merchantBatchService.publicTracking(token).withCards(orderTracking.legacyCards(token)));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            .body(body);
    }

    @GetMapping("/order")
    public ResponseEntity<MerchantBatchService.PublicTrackingResponse> order(
        @RequestHeader(value="X-NXR-Tracking-Token",required=false) String token,
        @RequestHeader(value="X-NXR-Tracking-Access",required=false) String access) {
        return tracking(token,access);
    }

    @PostMapping("/verify")
    public ResponseEntity<TrackingAccessService.Verified> verify(@RequestBody TrackingAccessService.VerifyRequest request,
        jakarta.servlet.http.HttpServletRequest http) {
        if(trackingAccess==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Unable to verify this order");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer")
            .header("X-Content-Type-Options","nosniff").body(trackingAccess.verify(request,clientAddress==null?http.getRemoteAddr():clientAddress.source(http)));
    }

    @GetMapping("/photos/{cardId}/{side}")
    public ResponseEntity<Resource> photo(@PathVariable long cardId,@PathVariable String side,
        @RequestHeader(value="X-NXR-Tracking-Token",required=false) String token,
        @RequestHeader(value="X-NXR-Tracking-Access",required=false) String access) {
        requireAccess(token,access);
        if(orderTracking==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
            .header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff")
            .body(orderTracking.photo(token,cardId,side));
    }
    private void requireAccess(String token,String access){
        if(trackingAccess==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Unable to verify this order");
        trackingAccess.requireAccess(token,access);
    }

    // Never send credential-bearing paths or submitted contact values to the global request logger.
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<java.util.Map<String,Object>> trackingFailure(org.springframework.web.server.ResponseStatusException error) {
        int status=error.getStatusCode().value();
        if(!java.util.Set.of(400,401,404,429,503).contains(status))status=503;
        String message=switch(status){case 401 -> "Unable to verify this order";case 404 -> "Order or image not found";case 429 -> "Please try again later";case 400 -> "Invalid request";default -> "Order verification is temporarily unavailable";};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(java.util.Map.of("status",status,"message",message));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<java.util.Map<String,Object>> unexpectedTrackingFailure() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(java.util.Map.of("status",503,"message","Order verification is temporarily unavailable"));
    }
}
