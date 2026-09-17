package com.nxr.platform.admin;

import com.nxr.platform.customer.OrderCardIdentityService;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminCardIdentityController {
    private final OrderCardIdentityService identities;
    public AdminCardIdentityController(OrderCardIdentityService identities) { this.identities=identities; }
    @ModelAttribute public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping("/orders/{orderId}/card-identities")
    @PreAuthorize("@ss.hasPermi('nxr:order:list')")
    public OrderCardIdentityService.OrderIdentities get(@PathVariable long orderId) { return identities.admin(SecurityUtils.getUserId(),orderId); }
    @PostMapping("/orders/{orderId}/card-identities")
    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:workbench')")
    public OrderCardIdentityService.OrderIdentities allocate(@PathVariable long orderId) { return identities.allocateForAdmin(SecurityUtils.getUserId(),orderId); }
    @GetMapping("/card-identities/lookup")
    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:workbench')")
    public OrderCardIdentityService.CardIdentity lookup(@RequestParam String code) { return identities.lookup(SecurityUtils.getUserId(),code); }
}
