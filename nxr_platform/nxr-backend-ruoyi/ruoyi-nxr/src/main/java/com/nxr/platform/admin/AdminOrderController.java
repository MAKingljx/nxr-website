package com.nxr.platform.admin;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.customer.CustomerPortalService;
import com.nxr.platform.customer.OrderFulfillmentService;
import com.nxr.platform.customer.OrderWorkbenchService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OrderWorkbenchService orderWorkbenchService;
    private final OrderAccessScopeService orderAccessScopeService;

    public AdminOrderController(
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService
    ) {
        this(customerPortalService, orderFulfillmentService, null, null);
    }

    public AdminOrderController(
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService,
        OrderWorkbenchService orderWorkbenchService
    ) {
        this(customerPortalService, orderFulfillmentService, orderWorkbenchService, null);
    }

    @Autowired
    public AdminOrderController(
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService,
        OrderWorkbenchService orderWorkbenchService,
        OrderAccessScopeService orderAccessScopeService
    ) {
        this.customerPortalService = customerPortalService;
        this.orderFulfillmentService = orderFulfillmentService;
        this.orderWorkbenchService = orderWorkbenchService;
        this.orderAccessScopeService = orderAccessScopeService;
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
        requireOrderAccess(orderId);
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
        requireOrderAccess(orderId);
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
        requireOrderAccess(orderId);
        return AjaxResult.success(customerPortalService.rejectPayment(orderId, paymentId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:manage')")
    @Log(title = "订单进度", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/status")
    public AjaxResult updateStatus(
        @PathVariable long orderId,
        @RequestBody CustomerPortalService.UpdateOrderStatusRequest request
    ) {
        requireOrderAccess(orderId);
        return AjaxResult.success(customerPortalService.updateOrderStatusByAdmin(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @Log(title = "订单物流", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/shipments")
    public AjaxResult createShipment(
        @PathVariable long orderId,
        @RequestBody CustomerPortalService.CreateShipmentRequest request
    ) {
        requireOrderAccess(orderId);
        return AjaxResult.success(customerPortalService.createAdminShipment(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @Log(title = "订单物流签收", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/shipments/{shipmentId}/delivered")
    public AjaxResult markShipmentDelivered(@PathVariable long orderId, @PathVariable long shipmentId) {
        requireOrderAccess(orderId);
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
        requireOrderAccess(orderId);
        requireSubmissionAccess(submissionId);
        return AjaxResult.success(customerPortalService.linkOrderItemSubmission(orderId, itemId, submissionId, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:grading,nxr:order:shipping,nxr:order:support,nxr:order:payment')")
    @GetMapping("/{orderId}/operations")
    public AjaxResult orderOperations(@PathVariable long orderId) {
        requireOrderAccess(orderId);
        return AjaxResult.success(orderFulfillmentService.loadAdminOperations(orderId));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse')")
    @GetMapping("/intake/lookup")
    public AjaxResult lookupIntake(@RequestParam String intakeCode) {
        OrderFulfillmentService.IntakeLookup result = orderFulfillmentService.lookupIntake(intakeCode);
        requireOrderAccess(result.orderId());
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse')")
    @Log(title = "订单扫码入库", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/intake/receive")
    public AjaxResult receiveOrder(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.ReceiveOrderRequest request
    ) {
        requireOrderAccess(orderId);
        return AjaxResult.success(orderFulfillmentService.receiveOrder(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:support')")
    @Log(title = "订单入库异常", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/exceptions")
    public AjaxResult createException(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.OrderExceptionRequest request
    ) {
        requireOrderAccess(orderId);
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
        requireOrderAccess(orderId);
        return AjaxResult.success(orderFulfillmentService.resolveException(orderId, exceptionId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:grading')")
    @Log(title = "订单作业任务", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/tasks")
    public AjaxResult createWorkTask(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.WorkTaskRequest request
    ) {
        requireOrderAccess(orderId);
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
        requireOrderAccess(orderId);
        return AjaxResult.success(orderFulfillmentService.updateWorkTask(orderId, taskId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:grading')")
    @Log(title = "订单终检", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/quality-check")
    public AjaxResult qualityCheck(
        @PathVariable long orderId,
        @RequestBody OrderFulfillmentService.QualityCheckRequest request
    ) {
        requireOrderAccess(orderId);
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
        requireOrderAccess(orderId);
        return AjaxResult.success(orderFulfillmentService.addTrackingEvent(orderId, shipmentId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:grading,nxr:order:workbench')")
    @GetMapping("/{orderId}/workbench")
    public AjaxResult workbench(@PathVariable long orderId) {
        requireOrderAccess(orderId);
        return AjaxResult.success(requireWorkbench().snapshot(orderId));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:grading,nxr:order:workbench')")
    @Log(title = "订单工位锁定", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/workbench/start")
    public AjaxResult startWorkbench(@PathVariable long orderId) {
        requireOrderAccess(orderId);
        return AjaxResult.success(requireWorkbench().start(orderId, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:grading,nxr:order:workbench')")
    @Log(title = "订单逐卡扫码", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/workbench/scan")
    public AjaxResult scanWorkbench(
        @PathVariable long orderId,
        @RequestBody OrderWorkbenchService.ScanRequest request
    ) {
        requireOrderAccess(orderId);
        return AjaxResult.success(requireWorkbench().scan(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:grading,nxr:order:workbench')")
    @Log(title = "订单打包复核", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/workbench/packing-check")
    public AjaxResult packingCheck(
        @PathVariable long orderId,
        @RequestBody OrderWorkbenchService.PackingCheckRequest request
    ) {
        requireOrderAccess(orderId);
        return AjaxResult.success(requireWorkbench().completePackingCheck(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:grading,nxr:order:workbench')")
    @Log(title = "订单标签导出", businessType = BusinessType.EXPORT)
    @PostMapping("/{orderId}/workbench/label-export")
    public ResponseEntity<byte[]> exportLabels(
        @PathVariable long orderId,
        @RequestBody(required = false) OrderWorkbenchService.ExportRequest request
    ) {
        requireOrderAccess(orderId);
        return exportResponse(requireWorkbench().exportLabels(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:grading,nxr:order:workbench')")
    @Log(title = "订单打包清单导出", businessType = BusinessType.EXPORT)
    @PostMapping("/{orderId}/workbench/manifest-export")
    public ResponseEntity<byte[]> exportManifest(
        @PathVariable long orderId,
        @RequestBody(required = false) OrderWorkbenchService.ExportRequest request
    ) {
        requireOrderAccess(orderId);
        return exportResponse(requireWorkbench().exportManifest(orderId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:support')")
    @Log(title = "订单客服工单", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/tickets/{ticketId}")
    public AjaxResult updateTicket(
        @PathVariable long orderId,
        @PathVariable long ticketId,
        @RequestBody OrderFulfillmentService.AdminTicketRequest request
    ) {
        requireOrderAccess(orderId);
        return AjaxResult.success(orderFulfillmentService.updateTicketByAdmin(orderId, ticketId, SecurityUtils.getUserId(), request));
    }

    private OrderWorkbenchService requireWorkbench() {
        if (orderWorkbenchService == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Order workbench is unavailable"
            );
        }
        return orderWorkbenchService;
    }

    private void requireOrderAccess(long orderId) {
        if (orderAccessScopeService != null) {
            orderAccessScopeService.requireAccessibleOrder(SecurityUtils.getUserId(), orderId);
        }
    }

    private void requireSubmissionAccess(long submissionId) {
        if (orderAccessScopeService != null) {
            orderAccessScopeService.requireAccessibleSubmission(SecurityUtils.getUserId(), submissionId);
        }
    }

    private ResponseEntity<byte[]> exportResponse(OrderWorkbenchService.ExportFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, file.contentType());
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(file.content());
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:support')")
    @Log(title = "回寄方案变更审核", businessType = BusinessType.UPDATE)
    @PostMapping("/{orderId}/shipping-changes/{requestId}/review")
    public AjaxResult reviewShippingChange(
        @PathVariable long orderId,
        @PathVariable long requestId,
        @RequestBody OrderFulfillmentService.ReviewShippingChangeRequest request
    ) {
        requireOrderAccess(orderId);
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
        requireOrderAccess(orderId);
        return AjaxResult.success(orderFulfillmentService.settleShippingChange(orderId, requestId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @GetMapping("/shipping-options")
    public AjaxResult listShippingOptions(@RequestParam(required = false) String country) {
        return AjaxResult.success(orderFulfillmentService.listShippingOptions(country, true));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @GetMapping("/service-price")
    public AjaxResult getServicePrice(@RequestParam(defaultValue = "USD") String currencyCode) {
        return AjaxResult.success(orderFulfillmentService.activeServicePrice(currencyCode));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:config')")
    @GetMapping("/service-prices")
    public AjaxResult getServicePrices() {
        return AjaxResult.success(orderFulfillmentService.activeServicePrices());
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:config')")
    @Log(title = "评级服务价格配置", businessType = BusinessType.UPDATE)
    @PostMapping("/service-price")
    public AjaxResult saveServicePrice(@RequestBody OrderFulfillmentService.ServicePriceRequest request) {
        return AjaxResult.success(orderFulfillmentService.saveServicePrice(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:config')")
    @Log(title = "回寄方案配置", businessType = BusinessType.UPDATE)
    @PostMapping("/shipping-options")
    public AjaxResult saveShippingOption(@RequestBody OrderFulfillmentService.ShippingOptionRequest request) {
        return AjaxResult.success(orderFulfillmentService.saveShippingOption(null, request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:order:config')")
    @Log(title = "回寄方案配置", businessType = BusinessType.UPDATE)
    @PostMapping("/shipping-options/{optionId}")
    public AjaxResult saveShippingOption(
        @PathVariable long optionId,
        @RequestBody OrderFulfillmentService.ShippingOptionRequest request
    ) {
        return AjaxResult.success(orderFulfillmentService.saveShippingOption(optionId, request));
    }
}
