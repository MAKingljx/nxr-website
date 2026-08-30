package com.nxr.platform.admin;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/media")
public class AdminMediaController {

    private final AdminMediaService adminMediaService;

    public AdminMediaController(AdminMediaService adminMediaService) {
        this.adminMediaService = adminMediaService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:media:list')")
    @GetMapping("/queue")
    public AjaxResult mediaQueue(
        @RequestParam(required = false) String query,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int pageSize
    ) {
        return AjaxResult.success(adminMediaService.loadQueue(query, page, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('nxr:media:import')")
    @Log(title = "媒体导入", businessType = BusinessType.IMPORT)
    @PostMapping("/import-folder")
    public AjaxResult importFolder(
        @RequestPart(name = "image_files", required = false) List<MultipartFile> imageFiles
    ) {
        return AjaxResult.success(adminMediaService.importFolder(imageFiles));
    }

    @PreAuthorize("@ss.hasPermi('nxr:media:import')")
    @Log(title = "录入媒体上传", businessType = BusinessType.IMPORT)
    @PostMapping("/submissions/{submissionId}/staged")
    public AjaxResult importSubmissionMedia(
        @PathVariable long submissionId,
        @RequestPart(name = "image_files", required = false) List<MultipartFile> imageFiles
    ) {
        return AjaxResult.success(adminMediaService.importSubmissionMedia(submissionId, imageFiles));
    }

    @PreAuthorize("@ss.hasPermi('nxr:media:publish')")
    @Log(title = "媒体发布", businessType = BusinessType.UPDATE)
    @PostMapping("/submissions/{submissionId}/publish")
    public AjaxResult publishSubmission(@PathVariable long submissionId) {
        return AjaxResult.success(adminMediaService.publishSubmission(submissionId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:media:publish')")
    @Log(title = "媒体批量发布", businessType = BusinessType.UPDATE)
    @PostMapping("/batch-publish")
    public AjaxResult publishSubmissions(@RequestBody AdminMediaService.MediaBatchPublishRequest request) {
        return AjaxResult.success(
            adminMediaService.publishSubmissions(request == null ? null : request.submissionIds())
        );
    }
}
