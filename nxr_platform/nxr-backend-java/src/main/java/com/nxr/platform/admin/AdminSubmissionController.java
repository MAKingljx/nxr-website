package com.nxr.platform.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/submissions")
public class AdminSubmissionController {

    private final AdminSubmissionService adminSubmissionService;

    public AdminSubmissionController(AdminSubmissionService adminSubmissionService) {
        this.adminSubmissionService = adminSubmissionService;
    }

    @GetMapping
    public AdminSubmissionService.SubmissionListResponse listSubmissions(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query
    ) {
        return adminSubmissionService.listSubmissions(page, pageSize, status, query);
    }

    @GetMapping("/{submissionId}")
    public AdminSubmissionService.SubmissionDetailResponse submissionDetail(@PathVariable long submissionId) {
        return adminSubmissionService.loadSubmission(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminSubmissionService.SubmissionDetailResponse createSubmission(
        @Valid @RequestBody CreateSubmissionPayload payload
    ) {
        return adminSubmissionService.createSubmission(new AdminSubmissionService.CreateSubmissionRequest(
            payload.certId(),
            payload.cardName(),
            payload.yearLabel(),
            payload.brandName(),
            payload.playerName(),
            payload.varietyName(),
            payload.setName(),
            payload.cardNumber(),
            payload.languageCode(),
            payload.populationValue(),
            payload.centeringScore(),
            payload.edgesScore(),
            payload.cornersScore(),
            payload.surfaceScore(),
            payload.entryNotes()
        ));
    }

    public record CreateSubmissionPayload(
        @NotBlank String certId,
        @NotBlank String cardName,
        String yearLabel,
        @NotBlank String brandName,
        String playerName,
        String varietyName,
        @NotBlank String setName,
        @NotBlank String cardNumber,
        @NotBlank String languageCode,
        @NotNull @Min(1) Integer populationValue,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal centeringScore,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal edgesScore,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal cornersScore,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal surfaceScore,
        String entryNotes
    ) {
    }
}
