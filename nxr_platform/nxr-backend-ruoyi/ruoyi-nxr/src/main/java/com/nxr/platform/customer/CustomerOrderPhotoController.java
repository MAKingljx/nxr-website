package com.nxr.platform.customer;

import com.ruoyi.common.annotation.Anonymous;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Anonymous
@RestController
@RequestMapping("/api/customer/order-photos")
public class CustomerOrderPhotoController {
    private final CustomerAuthService auth;
    private final CustomerOrderPhotoService photos;
    public CustomerOrderPhotoController(CustomerAuthService auth, CustomerOrderPhotoService photos) { this.auth = auth; this.photos = photos; }
    @PostMapping public CustomerOrderPhotoService.Photo upload(@RequestHeader(value = "X-NXR-Customer-Token", required = false) String token,
        @RequestParam("file") MultipartFile file) { return photos.upload(auth.requireCustomer(token).id(), file); }
    @GetMapping public Object unused(@RequestHeader(value = "X-NXR-Customer-Token", required = false) String token) {
        return photos.unused(auth.requireCustomer(token).id());
    }
    @GetMapping("/{photoId}") public ResponseEntity<Resource> preview(@RequestHeader(value = "X-NXR-Customer-Token", required = false) String token, @PathVariable long photoId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").body(photos.readOwned(auth.requireCustomer(token).id(), photoId, false));
    }
    @DeleteMapping("/{photoId}") public Object remove(@RequestHeader(value = "X-NXR-Customer-Token", required = false) String token, @PathVariable long photoId) {
        photos.removeUnused(auth.requireCustomer(token).id(), photoId); return Map.of("removed", true);
    }
}
