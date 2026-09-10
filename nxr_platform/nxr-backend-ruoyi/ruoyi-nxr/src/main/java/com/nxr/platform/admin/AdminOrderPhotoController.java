package com.nxr.platform.admin;

import com.nxr.platform.customer.CustomerOrderPhotoService;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/order-photos")
public class AdminOrderPhotoController {
    private final CustomerOrderPhotoService photos;
    private final OrderAccessScopeService scope;
    public AdminOrderPhotoController(CustomerOrderPhotoService photos, OrderAccessScopeService scope) { this.photos = photos; this.scope = scope; }

    @PreAuthorize("@ss.hasPermi('nxr:order:list')")
    @GetMapping("/{photoId}") public ResponseEntity<Resource> preview(@PathVariable long photoId) {
        Long orderId = photos.attachedOrderId(photoId);
        if (orderId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        scope.requireAccessibleOrder(SecurityUtils.getUserId(), orderId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").body(photos.readAttached(orderId, photoId));
    }

    @PreAuthorize("@ss.hasRole('admin') or @ss.hasPermi('nxr:order:manage')")
    @GetMapping("/capacity") public AjaxResult capacity() { return AjaxResult.success(photos.capacityStatus()); }
}
