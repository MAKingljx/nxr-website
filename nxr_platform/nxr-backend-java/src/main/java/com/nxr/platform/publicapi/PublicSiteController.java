package com.nxr.platform.publicapi;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public")
public class PublicSiteController {

    private final PublicSiteService publicSiteService;

    public PublicSiteController(PublicSiteService publicSiteService) {
        this.publicSiteService = publicSiteService;
    }

    @GetMapping("/overview")
    public PublicSiteService.PublicOverviewResponse overview() {
        return publicSiteService.loadOverview();
    }

    @GetMapping("/cards/{certId}")
    public PublicSiteService.PublicCardResponse publishedCard(@PathVariable String certId) {
        return publicSiteService.loadPublishedCard(certId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found"));
    }
}
