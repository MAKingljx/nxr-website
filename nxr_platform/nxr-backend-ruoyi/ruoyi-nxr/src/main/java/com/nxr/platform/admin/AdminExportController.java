package com.nxr.platform.admin;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/exports")
public class AdminExportController {

    private final AdminExportService adminExportService;
    private final OrderAccessScopeService accessScopeService;

    public AdminExportController(AdminExportService adminExportService) {
        this(adminExportService, null);
    }

    @Autowired
    public AdminExportController(AdminExportService adminExportService, OrderAccessScopeService accessScopeService) {
        this.adminExportService = adminExportService;
        this.accessScopeService = accessScopeService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:export:list')")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody AdminExportService.ExportRequest request) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "Global card export");
        return AjaxResult.success(adminExportService.preview(request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:export:generate')")
    @Log(title = "Excel导出", businessType = BusinessType.EXPORT)
    @PostMapping("/generate")
    public AjaxResult generate(@RequestBody AdminExportService.ExportRequest request) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "Global card export");
        return AjaxResult.success(adminExportService.generate(request, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('nxr:export:list')")
    @GetMapping
    public AjaxResult list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "Export history");
        return AjaxResult.success(adminExportService.listExports(page, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('nxr:export:list')")
    @GetMapping("/{filename}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String filename) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "Export download");
        AdminExportService.DownloadableExport downloadableExport = adminExportService.resolveDownload(filename);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadableExport.filename() + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(downloadableExport.resource());
    }

    @PreAuthorize("@ss.hasPermi('nxr:export:remove')")
    @Log(title = "Excel导出", businessType = BusinessType.DELETE)
    @DeleteMapping("/{filename}")
    public AjaxResult delete(@PathVariable String filename) {
        accessScopeService.requireUnrestricted(SecurityUtils.getUserId(), "Export deletion");
        return AjaxResult.success(adminExportService.deleteExport(filename));
    }
}
