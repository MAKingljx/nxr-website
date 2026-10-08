package com.nxr.platform.admin;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/card-operators")
public class AdminCardOperatorController {
    private final AdminCardOperatorService service;
    public AdminCardOperatorController(AdminCardOperatorService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('nxr:card-user:list')")
    public AjaxResult list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
                           @RequestParam(required = false) String query, @RequestParam(required = false) String status) {
        return AjaxResult.success(service.list(SecurityUtils.getUserId(), page, pageSize, query, status));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('nxr:card-user:add')")
    @Log(title = "卡片上传账号创建", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    public AjaxResult create(@RequestBody AdminCardOperatorService.CreateRequest request) {
        return AjaxResult.success(service.create(SecurityUtils.getUserId(), request));
    }

    @PutMapping("/{userId}/status")
    @PreAuthorize("@ss.hasPermi('nxr:card-user:edit')")
    @Log(title = "卡片上传账号状态", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    public AjaxResult changeStatus(@PathVariable long userId, @RequestBody AdminCardOperatorService.StatusRequest request) {
        return AjaxResult.success(service.changeStatus(SecurityUtils.getUserId(), userId, request));
    }

    @PutMapping("/{userId}/password")
    @PreAuthorize("@ss.hasPermi('nxr:card-user:resetPwd')")
    @Log(title = "卡片上传账号密码重置", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    public AjaxResult resetPassword(@PathVariable long userId, @RequestBody AdminCardOperatorService.PasswordRequest request) {
        return AjaxResult.success(service.resetPassword(SecurityUtils.getUserId(), userId, request));
    }
}
