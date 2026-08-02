package com.nxr.platform.admin;

import com.nxr.platform.customer.CustomerPortalService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff operations for customer grading orders, payments and shipments. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final CustomerPortalService customerPortalService;

    public AdminOrderController(CustomerPortalService customerPortalService) {
        this.customerPortalService = customerPortalService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:list')")
    @GetMapping
    public AjaxResult listOrders(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query
    ) {
        return AjaxResult.success(customerPortalService.listAdminOrders(page, pageSize, status, query));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:list')")
    @GetMapping("/{orderId}")
    public AjaxResult orderDetail(@PathVariable long orderId) {
        return AjaxResult.success(customerPortalService.requireAdminOrder(orderId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:manage')")
    @Log(title = "订单确认收款", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/payments/{paymentId}/confirm")
    public AjaxResult confirmPayment(
        @PathVariable long orderId,
        @PathVariable long paymentId,
        @RequestBody CustomerPortalService.ConfirmPaymentRequest request
    ) {
        return AjaxResult.success(customerPortalService.confirmPayment(orderId, paymentId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:manage')")
    @Log(title = "订单驳回收款", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/payments/{paymentId}/reject")
    public AjaxResult rejectPayment(
        @PathVariable long orderId,
        @PathVariable long paymentId,
        @RequestBody CustomerPortalService.RejectPaymentRequest request
    ) {
        return AjaxResult.success(customerPortalService.rejectPayment(orderId, paymentId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:manage')")
    @Log(title = "订单进度", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/status")
    public AjaxResult updateStatus(
        @PathVariable long orderId,
        @RequestBody CustomerPortalService.UpdateOrderStatusRequest request
    ) {
        return AjaxResult.success(customerPortalService.updateOrderStatusByAdmin(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:manage')")
    @Log(title = "订单物流", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/shipments")
    public AjaxResult createShipment(
        @PathVariable long orderId,
        @RequestBody CustomerPortalService.CreateShipmentRequest request
    ) {
        return AjaxResult.success(customerPortalService.createAdminShipment(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:manage')")
    @Log(title = "订单物流签收", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/shipments/{shipmentId}/delivered")
    public AjaxResult markShipmentDelivered(@PathVariable long orderId, @PathVariable long shipmentId) {
        return AjaxResult.success(customerPortalService.markShipmentDelivered(orderId, shipmentId, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:manage')")
    @Log(title = "订单关联评分", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/items/{itemId}/link-submission")
    public AjaxResult linkSubmission(
        @PathVariable long orderId,
        @PathVariable long itemId,
        @RequestParam long submissionId
    ) {
        return AjaxResult.success(customerPortalService.linkOrderItemSubmission(orderId, itemId, submissionId, SecurityUtils.getUserId()));
    }
}
