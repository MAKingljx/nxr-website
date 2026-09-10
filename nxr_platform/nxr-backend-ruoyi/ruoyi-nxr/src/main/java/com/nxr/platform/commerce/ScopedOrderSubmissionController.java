package com.nxr.platform.commerce;

import com.nxr.platform.admin.AdminSubmissionService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders/{orderId}/items/{itemId}/submission")
public class ScopedOrderSubmissionController {
    private final ScopedOrderSubmissionService service;
    public ScopedOrderSubmissionController(ScopedOrderSubmissionService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('nxr:order:grading')")
    @Log(title = "订单逐卡评级录入", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@PathVariable long orderId, @PathVariable long itemId,
                             @RequestBody AdminSubmissionService.MutateSubmissionRequest request) {
        return AjaxResult.success(service.create(SecurityUtils.getUserId(), orderId, itemId, request));
    }
}
