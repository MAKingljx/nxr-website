package com.nxr.platform.admin;

import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/waitlist")
public class AdminWaitlistController {

    private final AdminWaitlistService adminWaitlistService;

    public AdminWaitlistController(AdminWaitlistService adminWaitlistService) {
        this.adminWaitlistService = adminWaitlistService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:waitlist:list')")
    @GetMapping
    public AjaxResult listWaitlist(
        @RequestParam(defaultValue = "") String query,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return AjaxResult.success(adminWaitlistService.listWaitlist(query, page, pageSize));
    }
}
