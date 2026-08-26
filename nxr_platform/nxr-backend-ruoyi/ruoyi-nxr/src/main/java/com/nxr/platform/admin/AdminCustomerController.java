package com.nxr.platform.admin;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff management for customer accounts and their collector history. */
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final AdminCustomerService adminCustomerService;

    public AdminCustomerController(AdminCustomerService adminCustomerService) {
        this.adminCustomerService = adminCustomerService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:list')")
    @GetMapping
    public AjaxResult listCustomers(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query
    ) {
        return AjaxResult.success(adminCustomerService.listCustomers(page, pageSize, status, query));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:list')")
    @GetMapping("/{customerId}")
    public AjaxResult customerDetail(@PathVariable long customerId) {
        return AjaxResult.success(adminCustomerService.requireCustomer(customerId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:manage')")
    @Log(title = "客户账号状态", businessType = BusinessType.UPDATE)
    @PutMapping("/{customerId}/status")
    public AjaxResult updateCustomerStatus(
        @PathVariable long customerId,
        @RequestBody AdminCustomerService.UpdateCustomerStatusRequest request
    ) {
        return AjaxResult.success(adminCustomerService.updateCustomerStatus(customerId, request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:manage')")
    @Log(title = "客户账号类型", businessType = BusinessType.UPDATE)
    @PutMapping("/{customerId}/type")
    public AjaxResult updateCustomerType(
        @PathVariable long customerId,
        @RequestBody AdminCustomerService.UpdateCustomerTypeRequest request
    ) {
        return AjaxResult.success(adminCustomerService.updateCustomerType(customerId, request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:manage')")
    @Log(title = "客户会话失效", businessType = BusinessType.UPDATE)
    @PostMapping("/{customerId}/sessions/revoke")
    public AjaxResult revokeCustomerSessions(@PathVariable long customerId) {
        return AjaxResult.success(adminCustomerService.revokeCustomerSessions(customerId));
    }
}
