package com.nxr.platform.admin;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.payments.FinanceExceptionReviewService;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders/{orderId}/finance-exceptions")
@PreAuthorize("@ss.hasAnyPermi('nxr:order:payment,nxr:customer:finance')")
public class AdminFinanceExceptionController {
    private final FinanceExceptionReviewService service;
    private final OrderAccessScopeService orderAccess;

    public AdminFinanceExceptionController(FinanceExceptionReviewService service, OrderAccessScopeService orderAccess) {
        this.service = service;
        this.orderAccess = orderAccess;
    }

    @GetMapping
    public AjaxResult list(@PathVariable long orderId) {
        orderAccess.requireAccessibleOrder(orderId);
        return AjaxResult.success(service.listForOrder(orderId));
    }

    @GetMapping("/review-context")
    public AjaxResult context(@PathVariable long orderId) {
        orderAccess.requireAccessibleOrder(orderId);
        return AjaxResult.success(service.reviewContext(orderId));
    }

    @PostMapping("/review")
    @Log(title = "订单财务异常处理", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    public AjaxResult review(@PathVariable long orderId, @RequestBody FinanceExceptionReviewService.ReviewRequest request) {
        orderAccess.requireAccessibleOrder(orderId);
        return AjaxResult.success(service.review(orderId, SecurityUtils.getUserId(), request));
    }
}
