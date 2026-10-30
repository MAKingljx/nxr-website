package com.nxr.platform.admission;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/order-admissions")
public class AdminOrderAdmissionController {

    private final OrderAdmissionService service;
    private final OrderAccessScopeService accessScope;

    public AdminOrderAdmissionController(OrderAdmissionService service, OrderAccessScopeService accessScope) {
        this.service = service;
        this.accessScope = accessScope;
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:admission,nxr:order:manage')")
    @GetMapping("/{orderId}")
    public AjaxResult admission(@PathVariable long orderId) {
        accessScope.requireAccessibleOrder(SecurityUtils.getUserId(), orderId);
        return AjaxResult.success(service.requireAdminAdmission(orderId));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:admission,nxr:order:manage')")
    @Log(title = "订单受理审核", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/decision")
    public AjaxResult decide(@PathVariable long orderId, @RequestBody OrderAdmissionService.DecisionRequest request) {
        accessScope.requireAccessibleOrder(SecurityUtils.getUserId(), orderId);
        return AjaxResult.success(service.decide(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:admission,nxr:order:manage')")
    @GetMapping("/partner-submits/{batchId}")
    public AjaxResult partnerSubmit(@PathVariable long batchId) {
        long userId = SecurityUtils.getUserId();
        var result = service.requirePartnerSubmitAdmission(batchId, id -> accessScope.requireAccessibleOrder(userId, id));
        if (!result.eligible() && !(SecurityUtils.hasPermi("nxr:order:manage") || SecurityUtils.hasPermi("nxr:order:warehouse")
            || SecurityUtils.hasPermi("nxr:order:shipping") || SecurityUtils.hasPermi("nxr:order:batch"))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Partner submission not found");
        }
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:admission,nxr:order:manage')")
    @Log(title = "合作伙伴送评受理审核", businessType = BusinessType.UPDATE)
    @PostMapping("/partner-submits/{batchId}/decision")
    public AjaxResult decidePartnerSubmit(@PathVariable long batchId, @RequestBody OrderAdmissionService.PartnerSubmitDecision request) {
        long userId = SecurityUtils.getUserId();
        return AjaxResult.success(service.decidePartnerSubmit(batchId, userId, request, id -> accessScope.requireAccessibleOrder(userId, id)));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:admission,nxr:order:config')")
    @GetMapping("/config")
    public AjaxResult config() {
        return AjaxResult.success(service.getConfig());
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:config')")
    @Log(title = "订单受理配置", businessType = BusinessType.UPDATE)
    @PutMapping("/config")
    public AjaxResult updateConfig(@RequestBody OrderAdmissionService.ConfigUpdate request) {
        return AjaxResult.success(service.updateConfig(request, SecurityUtils.getUserId()));
    }
}
