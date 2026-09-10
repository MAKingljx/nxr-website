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
