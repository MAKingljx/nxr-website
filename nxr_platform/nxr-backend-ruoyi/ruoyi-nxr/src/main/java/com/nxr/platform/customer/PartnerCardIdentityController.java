package com.nxr.platform.customer;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/agent/orders/{orderNo}/card-identities")
@PreAuthorize("@ss.hasAnyPermi('nxr:agent:workbench,nxr:agent:manage')")
public class PartnerCardIdentityController {
    private final AgentOperatorScopeService scope;
    private final OrderCardIdentityService identities;
    public PartnerCardIdentityController(AgentOperatorScopeService scope,OrderCardIdentityService identities) { this.scope=scope; this.identities=identities; }
    @ModelAttribute public void privateResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); response.addHeader("Vary","X-NXR-Agent-Id");
    }
    @GetMapping public OrderCardIdentityService.OrderIdentities get(
        @RequestHeader(name="X-NXR-Agent-Id",required=false) Long company,@PathVariable String orderNo) {
        return identities.customer(scope.currentMerchant(company),orderNo);
    }
    @PostMapping public OrderCardIdentityService.OrderIdentities allocate(
        @RequestHeader(name="X-NXR-Agent-Id",required=false) Long company,@PathVariable String orderNo) {
        return identities.allocateForCustomer(scope.currentMerchant(company),orderNo);
    }
}
