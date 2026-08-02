package com.nxr.platform.admin;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/brand-settings")
public class AdminBrandSettingsController {

    private final AdminBrandSettingsService adminBrandSettingsService;

    public AdminBrandSettingsController(AdminBrandSettingsService adminBrandSettingsService) {
        this.adminBrandSettingsService = adminBrandSettingsService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:brand:list')")
    @GetMapping
    public AjaxResult listBrands() {
        return AjaxResult.success(adminBrandSettingsService.listBrands());
    }

    @PreAuthorize("@ss.hasPermi('nxr:brand:add')")
    @Log(title = "品牌设置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult createBrand(@RequestBody AdminBrandSettingsService.BrandSettingRequest request) {
        return AjaxResult.success(adminBrandSettingsService.createBrand(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:brand:edit')")
    @Log(title = "品牌设置", businessType = BusinessType.UPDATE)
    @PutMapping("/{brandId}")
    public AjaxResult updateBrand(
        @PathVariable long brandId,
        @RequestBody AdminBrandSettingsService.BrandSettingRequest request
    ) {
        return AjaxResult.success(adminBrandSettingsService.updateBrand(brandId, request));
    }
}
