package com.nxr.platform.admin;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.nxr.platform.customer.MerchantWalletService;
import com.nxr.platform.commerce.OrderAccessScopeService;
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
    private final OrderAccessScopeService accessScopeService;

    public AdminCustomerController(AdminCustomerService adminCustomerService, OrderAccessScopeService accessScopeService) {
        this.adminCustomerService = adminCustomerService;
        this.accessScopeService = accessScopeService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:list')")
    @GetMapping
    public AjaxResult listCustomers(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.listCustomers(page, pageSize, status, query));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:list')")
    @GetMapping("/{customerId}")
    public AjaxResult customerDetail(@PathVariable long customerId) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.requireCustomer(customerId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:manage')")
    @Log(title = "客户账号状态", businessType = BusinessType.UPDATE)
    @PutMapping("/{customerId}/status")
    public AjaxResult updateCustomerStatus(
        @PathVariable long customerId,
        @RequestBody AdminCustomerService.UpdateCustomerStatusRequest request
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.updateCustomerStatus(customerId, request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:manage')")
    @Log(title = "客户账号类型", businessType = BusinessType.UPDATE)
    @PutMapping("/{customerId}/type")
    public AjaxResult updateCustomerType(
        @PathVariable long customerId,
        @RequestBody AdminCustomerService.UpdateCustomerTypeRequest request
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.updateCustomerType(customerId, request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:manage')")
    @Log(title = "客户会话失效", businessType = BusinessType.UPDATE)
    @PostMapping("/{customerId}/sessions/revoke")
    public AjaxResult revokeCustomerSessions(@PathVariable long customerId) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.revokeCustomerSessions(customerId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:list')")
    @GetMapping("/{customerId}/merchant-profile")
    public AjaxResult merchantProfile(@PathVariable long customerId) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.merchantProfile(customerId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:manage')")
    @Log(title = "企业客户资料", businessType = BusinessType.UPDATE)
    @PutMapping("/{customerId}/merchant-profile")
    public AjaxResult saveMerchantProfile(
        @PathVariable long customerId,
        @RequestBody MerchantWalletService.MerchantProfileRequest request
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.saveMerchantProfile(customerId, request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @GetMapping("/{customerId}/wallets")
    public AjaxResult wallets(@PathVariable long customerId) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.wallets(customerId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @GetMapping("/{customerId}/wallet-transactions")
    public AjaxResult walletTransactions(
        @PathVariable long customerId,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.walletTransactions(customerId, currencyCode, page, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @GetMapping("/{customerId}/wallet-recharges")
    public AjaxResult walletRecharges(
        @PathVariable long customerId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.walletRecharges(customerId, status, page, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @Log(title = "企业钱包充值确认", businessType = BusinessType.UPDATE)
    @PostMapping("/{customerId}/wallet-recharges/{rechargeId}/confirm")
    public AjaxResult confirmWalletRecharge(
        @PathVariable long customerId,
        @PathVariable long rechargeId,
        @RequestBody MerchantWalletService.RechargeReviewRequest request
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.reviewRecharge(
            customerId, rechargeId, SecurityUtils.getUserId(), true, request
        ));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @Log(title = "企业钱包充值拒绝", businessType = BusinessType.UPDATE)
    @PostMapping("/{customerId}/wallet-recharges/{rechargeId}/reject")
    public AjaxResult rejectWalletRecharge(
        @PathVariable long customerId,
        @PathVariable long rechargeId,
        @RequestBody MerchantWalletService.RechargeReviewRequest request
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "全局客户和钱包管理");
        return AjaxResult.success(adminCustomerService.reviewRecharge(
            customerId, rechargeId, SecurityUtils.getUserId(), false, request
        ));
    }
}
