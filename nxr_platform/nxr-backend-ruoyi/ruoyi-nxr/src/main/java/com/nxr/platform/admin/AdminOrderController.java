package com.nxr.platform.admin;

import com.nxr.platform.customer.CustomerPortalService;
import com.nxr.platform.customer.OrderFulfillmentService;
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
    private final OrderFulfillmentService orderFulfillmentService;

    public AdminOrderController(
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService
    ) {
        this.customerPortalService = customerPortalService;
        this.orderFulfillmentService = orderFulfillmentService;
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

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:payment')")
    @Log(title = "订单确认收款", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/payments/{paymentId}/confirm")
    public AjaxResult confirmPayment(
        @PathVariable long orderId,
        @PathVariable long paymentId,
        @RequestBody CustomerPortalService.ConfirmPaymentRequest request
    ) {
        return AjaxResult.success(customerPortalService.confirmPayment(orderId, paymentId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:payment')")
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

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @Log(title = "订单物流", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/shipments")
    public AjaxResult createShipment(
        @PathVariable long orderId,
        @RequestBody CustomerPortalService.CreateShipmentRequest request
    ) {
        return AjaxResult.success(customerPortalService.createAdminShipment(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @Log(title = "订单物流签收", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/shipments/{shipmentId}/delivered")
    public AjaxResult markShipmentDelivered(@PathVariable long orderId, @PathVariable long shipmentId) {
        return AjaxResult.success(customerPortalService.markShipmentDelivered(orderId, shipmentId, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:grading')")
    @Log(title = "订单关联评分", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/items/{itemId}/link-submission")
    public AjaxResult linkSubmission(
        @PathVariable long orderId,
        @PathVariable long itemId,
        @RequestParam long submissionId
    ) {
        return AjaxResult.success(customerPortalService.linkOrderItemSubmission(orderId, itemId, submissionId, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:grading,nxr:order:shipping,nxr:order:support,nxr:order:payment')")
    @GetMapping("/{orderId}/operations")
    public AjaxResult orderOperations(@PathVariable long orderId) {
        return AjaxResult.success(orderFulfillmentService.loadAdminOperations(orderId));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse')")
    @GetMapping("/intake/lookup")
    public AjaxResult lookupIntake(@RequestParam String intakeCode) {
        return AjaxResult.success(orderFulfillmentService.lookupIntake(intakeCode));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse')")
    @Log(title = "订单扫码入库", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/intake/receive")
    public AjaxResult receiveOrder(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.ReceiveOrderRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.receiveOrder(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:support')")
    @Log(title = "订单入库异常", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/exceptions")
    public AjaxResult createException(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.OrderExceptionRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.createException(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:support')")
    @Log(title = "订单异常处理", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/exceptions/{exceptionId}/resolve")
    public AjaxResult resolveException(
        @PathVariable long orderId,
        @PathVariable long exceptionId,
        @RequestBody OrderFulfillmentService.ResolveExceptionRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.resolveException(orderId, exceptionId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:grading')")
    @Log(title = "订单作业任务", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/tasks")
    public AjaxResult createWorkTask(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.WorkTaskRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.createWorkTask(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:grading')")
    @Log(title = "订单作业进度", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/tasks/{taskId}")
    public AjaxResult updateWorkTask(
        @PathVariable long orderId,
        @PathVariable long taskId,
        @RequestBody OrderFulfillmentService.WorkTaskUpdateRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.updateWorkTask(orderId, taskId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:grading')")
    @Log(title = "订单终检", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/quality-check")
    public AjaxResult qualityCheck(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.QualityCheckRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.qualityCheck(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @Log(title = "订单物流轨迹", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/shipments/{shipmentId}/tracking")
    public AjaxResult addTrackingEvent(
        @PathVariable long orderId,
        @PathVariable long shipmentId,
        @RequestBody OrderFulfillmentService.TrackingEventRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.addTrackingEvent(orderId, shipmentId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:support')")
    @Log(title = "订单客服工单", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/tickets/{ticketId}")
    public AjaxResult updateTicket(
        @PathVariable long orderId,
        @PathVariable long ticketId,
        @RequestBody OrderFulfillmentService.AdminTicketRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.updateTicketByAdmin(orderId, ticketId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:support')")
    @Log(title = "回寄方案变更审核", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/shipping-changes/{requestId}/review")
    public AjaxResult reviewShippingChange(
        @PathVariable long orderId,
        @PathVariable long requestId,
        @RequestBody OrderFulfillmentService.ReviewShippingChangeRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.reviewShippingChange(orderId, requestId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:payment')")
    @Log(title = "回寄差价结算", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/shipping-changes/{requestId}/settle")
    public AjaxResult settleShippingChange(
        @PathVariable long orderId,
        @PathVariable long requestId,
        @RequestBody OrderFulfillmentService.SettleShippingChangeRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.settleShippingChange(orderId, requestId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @GetMapping("/shipping-options")
    public AjaxResult listShippingOptions(@RequestParam(required = false) String country) {
        return AjaxResult.success(orderFulfillmentService.listShippingOptions(country, true));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @GetMapping("/service-price")
    public AjaxResult getServicePrice() {
        return AjaxResult.success(orderFulfillmentService.activeServicePrice());
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @Log(title = "评级服务价格配置", businessType = BusinessType.UPDATE)
    @PostMapping("/service-price")
    public AjaxResult saveServicePrice(@RequestBody OrderFulfillmentService.ServicePriceRequest request) {
        return AjaxResult.success(orderFulfillmentService.saveServicePrice(request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @Log(title = "回寄方案配置", businessType = BusinessType.UPDATE)
    @PostMapping("/shipping-options")
    public AjaxResult saveShippingOption(@RequestBody OrderFulfillmentService.ShippingOptionRequest request) {
        return AjaxResult.success(orderFulfillmentService.saveShippingOption(null, request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @Log(title = "回寄方案配置", businessType = BusinessType.UPDATE)
    @PostMapping("/shipping-options/{optionId}")
    public AjaxResult saveShippingOption(
        @PathVariable long optionId,
        @RequestBody OrderFulfillmentService.ShippingOptionRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.saveShippingOption(optionId, request));
    }
}
