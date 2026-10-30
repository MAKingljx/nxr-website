package com.nxr.platform.admin;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.customer.MerchantBatchService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff-only handling of merchant master parcels. */
@RestController
@RequestMapping("/api/admin/merchant-batches")
public class AdminMerchantBatchController {

    private final MerchantBatchService merchantBatchService;
    private final OrderAccessScopeService orderAccessScopeService;

    public AdminMerchantBatchController(MerchantBatchService merchantBatchService) {
        this(merchantBatchService, null);
    }

    @Autowired
    public AdminMerchantBatchController(
        MerchantBatchService merchantBatchService,
        OrderAccessScopeService orderAccessScopeService
    ) {
        this.merchantBatchService = merchantBatchService;
        this.orderAccessScopeService = orderAccessScopeService;
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:shipping,nxr:order:batch,nxr:order:admission')")
    @GetMapping
    public AjaxResult list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query
    ) {
        if (orderAccessScopeService == null) {
            return AjaxResult.success(merchantBatchService.listAdminBatches(page, pageSize, status, query));
        }
        return AjaxResult.success(merchantBatchService.listAdminBatches(
            page, pageSize, status, query, orderAccessScopeService.scopeForUser(SecurityUtils.getUserId()), admissionOnly()
        ));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:shipping,nxr:order:batch,nxr:order:admission')")
    @GetMapping("/{batchId}")
    public AjaxResult detail(@PathVariable long batchId) {
        return AjaxResult.success(requireAccessibleBatch(batchId));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping,nxr:order:batch')")
    @Log(title = "代理批次回寄", businessType = BusinessType.INSERT)
    @PostMapping("/{batchId}/outbound-shipment")
    public AjaxResult outboundShipment(
        @PathVariable long batchId,
        @RequestBody MerchantBatchService.BatchShipmentRequest request
    ) {
        requireAccessibleBatch(batchId);
        return AjaxResult.success(merchantBatchService.createOutboundShipment(batchId, SecurityUtils.getUserId(), request));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:warehouse,nxr:order:shipping,nxr:order:batch')")
    @Log(title = "代理批次物流签收", businessType = BusinessType.UPDATE)
    @PostMapping("/{batchId}/shipments/{shipmentId}/delivered")
    public AjaxResult delivered(@PathVariable long batchId, @PathVariable long shipmentId) {
        requireAccessibleBatch(batchId);
        return AjaxResult.success(merchantBatchService.markShipmentDelivered(batchId, shipmentId, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping,nxr:order:batch')")
    @GetMapping("/{batchId}/orders/{orderNo}/tracking-token")
    public AjaxResult trackingToken(@PathVariable long batchId, @PathVariable String orderNo) {
        requireAccessibleBatch(batchId);
        return AjaxResult.success(merchantBatchService.trackingLinkView(batchId, orderNo));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping,nxr:order:batch')")
    @Log(title = "商户批次查询链接", businessType = BusinessType.UPDATE)
    @PostMapping("/{batchId}/orders/{orderNo}/tracking-token/rotate")
    public AjaxResult rotateTrackingToken(@PathVariable long batchId, @PathVariable String orderNo) {
        requireAccessibleBatch(batchId);
        return AjaxResult.success(merchantBatchService.rotateTrackingTokenForStaff(batchId, orderNo));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping,nxr:order:batch')")
    @Log(title = "商户批次查询链接", businessType = BusinessType.DELETE)
    @DeleteMapping("/{batchId}/orders/{orderNo}/tracking-token")
    public AjaxResult revokeTrackingToken(@PathVariable long batchId, @PathVariable String orderNo) {
        requireAccessibleBatch(batchId);
        merchantBatchService.revokeTrackingTokenForStaff(batchId, orderNo);
        return AjaxResult.success();
    }

    private MerchantBatchService.BatchDetail requireAccessibleBatch(long batchId) {
        if (orderAccessScopeService != null && admissionOnly() && !merchantBatchService.isAgentSubmission(batchId)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Merchant batch not found");
        }
        MerchantBatchService.BatchDetail detail = merchantBatchService.requireAdminBatch(batchId);
        if (orderAccessScopeService != null) {
            long userId = SecurityUtils.getUserId();
            detail.orders().forEach(order -> orderAccessScopeService.requireAccessibleOrder(userId, order.orderId()));
        }
        return detail;
    }

    private boolean admissionOnly() {
        return !(SecurityUtils.hasPermi("nxr:order:manage") || SecurityUtils.hasPermi("nxr:order:warehouse")
            || SecurityUtils.hasPermi("nxr:order:shipping") || SecurityUtils.hasPermi("nxr:order:batch"));
    }
}
