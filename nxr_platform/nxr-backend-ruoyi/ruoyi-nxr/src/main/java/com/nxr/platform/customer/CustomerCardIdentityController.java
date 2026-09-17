package com.nxr.platform.customer;

import com.ruoyi.common.annotation.Anonymous;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@Anonymous
@RestController
@RequestMapping("/api/customer/orders/{orderNo}/card-identities")
public class CustomerCardIdentityController {
    private final CustomerAuthService auth;
    private final OrderCardIdentityService identities;
    public CustomerCardIdentityController(CustomerAuthService auth, OrderCardIdentityService identities) { this.auth=auth; this.identities=identities; }
    @ModelAttribute public void privateResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); response.addHeader("Vary","X-NXR-Customer-Token");
    }
    @GetMapping public OrderCardIdentityService.OrderIdentities get(
        @RequestHeader(name="X-NXR-Customer-Token",required=false) String token,@PathVariable String orderNo) {
        return identities.customer(auth.requireCustomer(token).id(),orderNo);
    }
    @PostMapping public OrderCardIdentityService.OrderIdentities allocate(
        @RequestHeader(name="X-NXR-Customer-Token",required=false) String token,@PathVariable String orderNo) {
        return identities.allocateForCustomer(auth.requireCustomer(token).id(),orderNo);
    }
}
