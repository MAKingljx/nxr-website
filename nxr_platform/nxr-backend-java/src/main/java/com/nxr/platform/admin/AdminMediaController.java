package com.nxr.platform.admin;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/media")
public class AdminMediaController {

    private final AdminMediaService adminMediaService;

    public AdminMediaController(AdminMediaService adminMediaService) {
        this.adminMediaService = adminMediaService;
    }

    @GetMapping("/queue")
    public AdminMediaService.MediaQueueResponse mediaQueue(@RequestParam(required = false) String query) {
        return adminMediaService.loadQueue(query);
    }

    @PostMapping("/import-folder")
    @ResponseStatus(HttpStatus.OK)
    public AdminMediaService.MediaImportResponse importFolder(
        @RequestPart(name = "image_files", required = false) List<MultipartFile> imageFiles
    ) {
        return adminMediaService.importFolder(imageFiles);
    }

    @PostMapping("/submissions/{submissionId}/publish")
    public AdminMediaService.MediaPublishResponse publishSubmission(@PathVariable long submissionId) {
        return adminMediaService.publishSubmission(submissionId);
    }
}
