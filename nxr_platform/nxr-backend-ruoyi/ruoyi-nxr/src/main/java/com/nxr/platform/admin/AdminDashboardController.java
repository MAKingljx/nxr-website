package com.nxr.platform.admin;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;
    private final OrderAccessScopeService accessScopeService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this(adminDashboardService, null);
    }

    @Autowired
    public AdminDashboardController(AdminDashboardService adminDashboardService, OrderAccessScopeService accessScopeService) {
        this.adminDashboardService = adminDashboardService;
        this.accessScopeService = accessScopeService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("@ss.hasPermi('nxr:dashboard:view')")
    public AjaxResult dashboard() {
        accessScopeService.requireUnrestrictedCardAccess(SecurityUtils.getUserId(), "Global card dashboard aggregates");
        boolean globalOrders = accessScopeService.scopeForUser(SecurityUtils.getUserId()).unrestricted();
        return AjaxResult.success(adminDashboardService.loadDashboard(
            globalOrders && (SecurityUtils.hasPermi("nxr:order:list") || SecurityUtils.hasPermi("nxr:order:manage")),
            globalOrders && (SecurityUtils.hasPermi("nxr:order:manage") || SecurityUtils.hasPermi("nxr:order:support")),
            globalOrders && (SecurityUtils.hasPermi("nxr:order:payment") || SecurityUtils.hasPermi("nxr:customer:finance")),
            SecurityUtils.hasPermi("nxr:waitlist:list")
        ));
    }
}
