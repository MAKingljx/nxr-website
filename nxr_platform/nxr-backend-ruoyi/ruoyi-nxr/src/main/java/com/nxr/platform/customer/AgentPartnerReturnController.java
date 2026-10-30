package com.nxr.platform.customer;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.nxr.platform.customer.PartnerReturnService.*;

/** Partner staff can inspect their own NXR return parcels; custody mutations stay staff-only. */
@RestController
@RequestMapping("/api/admin/agent/partner-return-parcels")
@PreAuthorize("@ss.hasAnyPermi('nxr:agent:workbench,nxr:agent:manage')")
public class AgentPartnerReturnController {
    private final PartnerReturnService service;private final AgentOperatorScopeService scope;
    public AgentPartnerReturnController(PartnerReturnService service,AgentOperatorScopeService scope){this.service=service;this.scope=scope;}
    @ModelAttribute public void privateResponses(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.addHeader("Vary","X-NXR-Agent-Id");}
    @GetMapping public Page<Parcel> parcels(@RequestHeader(name="X-NXR-Agent-Id",required=false) Long company,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) Long orderId,@RequestParam(required=false) Long batchId,@RequestParam(required=false) String statusCode){return service.partnerParcels(scope.currentMerchant(company),page,pageSize,orderId,batchId,statusCode);}
    @GetMapping("/{id}") public ParcelDetail detail(@RequestHeader(name="X-NXR-Agent-Id",required=false) Long company,@PathVariable long id){return service.partnerDetail(scope.currentMerchant(company),id);}
    @PostMapping("/{id}/delivered") public ParcelDetail delivered(@RequestHeader(name="X-NXR-Agent-Id",required=false) Long company,@PathVariable long id){return service.partnerDelivered(scope.currentMerchant(company),id);}
}
