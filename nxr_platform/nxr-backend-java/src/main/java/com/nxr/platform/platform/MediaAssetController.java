package com.nxr.platform.platform;

import com.nxr.platform.admin.AdminMediaService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaAssetController {

    private final AdminMediaService adminMediaService;

    public MediaAssetController(AdminMediaService adminMediaService) {
        this.adminMediaService = adminMediaService;
    }

    @GetMapping("/media/{stage}/{filename:.+}")
    public ResponseEntity<Resource> mediaAsset(
        @PathVariable String stage,
        @PathVariable String filename
    ) {
        AdminMediaService.ResolvedMediaAsset asset = adminMediaService.resolveMediaAsset(stage, filename);
        MediaType mediaType = MediaType.parseMediaType(asset.contentType());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(filename).build().toString())
            .contentType(mediaType)
            .body(new FileSystemResource(asset.path()));
    }
}
