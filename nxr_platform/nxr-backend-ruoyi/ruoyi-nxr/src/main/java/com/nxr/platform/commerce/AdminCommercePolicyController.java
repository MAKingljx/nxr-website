package com.nxr.platform.commerce;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Independent administration surface for quote and order-scope policy. */
@RestController
@RequestMapping("/api/admin/commerce-policy")
public class AdminCommercePolicyController {

    private final CommercePolicyService policyService;
    private final OrderAccessScopeService accessScopeService;

    public AdminCommercePolicyController(
        CommercePolicyService policyService,
        OrderAccessScopeService accessScopeService
    ) {
        this.policyService = policyService;
        this.accessScopeService = accessScopeService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:config')")
    @GetMapping("/catalog")
    public AjaxResult catalog() {
        return AjaxResult.success(policyService.catalog());
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:config')")
    @GetMapping("/quote-preview")
    public AjaxResult quotePreview(
        @RequestParam long customerId,
        @RequestParam String country,
        @RequestParam String currency,
        @RequestParam int count,
        @RequestParam(required = false) String shippingOptionCode
    ) {
        return AjaxResult.success(policyService.quoteForOrder(customerId, country, currency, count, shippingOptionCode));
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:config')")
    @Log(title = "分层价格策略", businessType = BusinessType.UPDATE)
    @PutMapping("/price-policy")
    public AjaxResult savePricePolicy(@RequestBody CommercePolicyService.PricePolicyRequest request) {
        return AjaxResult.success(policyService.savePricePolicy(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:config')")
    @Log(title = "重量运费策略", businessType = BusinessType.UPDATE)
    @PutMapping("/shipping-policy")
    public AjaxResult saveShippingPolicy(@RequestBody CommercePolicyService.ShippingPolicyRequest request) {
        return AjaxResult.success(policyService.saveShippingPolicy(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:config')")
    @Log(title = "业务线配置", businessType = BusinessType.UPDATE)
    @PutMapping("/business-line")
    public AjaxResult saveBusinessLine(@RequestBody CommercePolicyService.BusinessLineRequest request) {
        return AjaxResult.success(policyService.saveBusinessLine(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:config')")
    @Log(title = "作业中心配置", businessType = BusinessType.UPDATE)
    @PutMapping("/work-center")
    public AjaxResult saveWorkCenter(@RequestBody CommercePolicyService.WorkCenterRequest request) {
        return AjaxResult.success(policyService.saveWorkCenter(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:config')")
    @Log(title = "客户订单路由", businessType = BusinessType.UPDATE)
    @PutMapping("/customer-routing")
    public AjaxResult saveCustomerRouting(@RequestBody CommercePolicyService.CustomerRoutingRequest request) {
        return AjaxResult.success(policyService.saveCustomerRouting(request, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:scope')")
    @GetMapping("/staff-scopes")
    public AjaxResult staffScopes() {
        return AjaxResult.success(accessScopeService.scopeCatalog());
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:scope')")
    @Log(title = "员工订单范围", businessType = BusinessType.UPDATE)
    @PutMapping("/staff-scope")
    public AjaxResult saveStaffScope(@RequestBody OrderAccessScopeService.StaffScopeRequest request) {
        return AjaxResult.success(accessScopeService.saveStaffScope(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:commerce:scope')")
    @Log(title = "自有库存送评归属", businessType = BusinessType.UPDATE)
    @PutMapping("/submission-routing")
    public AjaxResult assignOwnedInventorySubmission(
        @RequestBody OrderAccessScopeService.SubmissionRoutingRequest request
    ) {
        return AjaxResult.success(accessScopeService.assignOwnedInventorySubmission(request));
    }
}
