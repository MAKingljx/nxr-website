package com.nxr.platform.admin;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/submissions")
public class AdminSubmissionController {

    private final AdminSubmissionService adminSubmissionService;

    public AdminSubmissionController(AdminSubmissionService adminSubmissionService) {
        this.adminSubmissionService = adminSubmissionService;
    }

    @PreAuthorize("@ss.hasPermi('nxr:entry:list')")
    @GetMapping
    public AjaxResult listSubmissions(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String certId,
        @RequestParam(required = false) String cardName,
        @RequestParam(required = false) String cardCategory,
        @RequestParam(required = false) String productType,
        @RequestParam(required = false) String brand,
        @RequestParam(required = false) String finalGrade,
        @RequestParam(required = false) String setName,
        @RequestParam(required = false) String language,
        @RequestParam(required = false) String enteredBy,
        @RequestParam(defaultValue = "entry_date") String sortBy,
        @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        return AjaxResult.success(adminSubmissionService.listSubmissions(
            new AdminSubmissionService.SubmissionListFilter(
                page,
                pageSize,
                status,
                query,
                certId,
                cardName,
                cardCategory,
                productType,
                brand,
                finalGrade,
                setName,
                language,
                enteredBy,
                sortBy,
                sortOrder
            )
        ));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:entry:list,nxr:entry:add')")
    @GetMapping("/generate-cert-id")
    public AjaxResult generateCertId() {
        return AjaxResult.success(new GenerateCertIdResponse(adminSubmissionService.generateCertificateId()));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:entry:list,nxr:entry:add')")
    @PostMapping("/calculate-grade")
    public AjaxResult calculateGrade(@Valid @RequestBody ScorePayload payload) {
        return AjaxResult.success(adminSubmissionService.calculateGrade(new AdminSubmissionService.ScoreRequest(
            payload.centeringScore(),
            payload.edgesScore(),
            payload.cornersScore(),
            payload.surfaceScore()
        )));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:entry:list,nxr:entry:add')")
    @PostMapping("/calculate-pop")
    public AjaxResult calculatePopulation(@RequestBody PopulationPayload payload) {
        return AjaxResult.success(adminSubmissionService.calculatePopulation(new AdminSubmissionService.PopulationCalculationRequest(
            payload.productType(),
            payload.cardCategory(),
            payload.cardName(),
            payload.setName(),
            payload.cardNumber(),
            payload.languageCode(),
            payload.movieName(),
            payload.releaseYear(),
            payload.productionCompany(),
            payload.filmType(),
            payload.sportsType(),
            payload.groupName(),
            payload.vintageClassification(),
            payload.finalGradeLabel(),
            payload.centeringScore(),
            payload.edgesScore(),
            payload.cornersScore(),
            payload.surfaceScore(),
            payload.currentSubmissionId()
        )));
    }

    @PreAuthorize("@ss.hasAnyPermi('nxr:entry:list,nxr:entry:add')")
    @PostMapping("/match-card")
    public AjaxResult matchCard(@RequestBody MatchCardPayload payload) {
        return AjaxResult.success(adminSubmissionService.matchCard(new AdminSubmissionService.MatchCardRequest(
            payload.productType(),
            payload.cardCategory(),
            payload.setName(),
            payload.cardNumber()
        )));
    }

    @PreAuthorize("@ss.hasPermi('nxr:entry:approve')")
    @Log(title = "卡牌审批", businessType = BusinessType.UPDATE)
    @PostMapping("/batch-approve")
    public AjaxResult batchApproveSubmissions(@RequestBody BatchApprovePayload payload) {
        return AjaxResult.success(adminSubmissionService.approveSubmissions(payload.submissionIds(), SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('nxr:entry:list')")
    @GetMapping("/{submissionId}")
    public AjaxResult submissionDetail(@PathVariable long submissionId) {
        return AjaxResult.success(adminSubmissionService.loadSubmission(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found")));
    }

    @PreAuthorize("@ss.hasPermi('nxr:entry:add')")
    @Log(title = "卡牌录入", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult createSubmission(@Valid @RequestBody MutateSubmissionPayload payload) {
        return AjaxResult.success(adminSubmissionService.createSubmission(toMutationRequest(payload, SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasPermi('nxr:entry:edit')")
    @Log(title = "卡牌编辑", businessType = BusinessType.UPDATE)
    @PutMapping("/{submissionId}")
    public AjaxResult updateSubmission(
        @PathVariable long submissionId,
        @Valid @RequestBody MutateSubmissionPayload payload
    ) {
        return AjaxResult.success(adminSubmissionService.updateSubmission(submissionId, toMutationRequest(payload, SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasPermi('nxr:entry:approve')")
    @Log(title = "卡牌审批", businessType = BusinessType.UPDATE)
    @PostMapping("/{submissionId}/approve")
    public AjaxResult approveSubmission(@PathVariable long submissionId) {
        return AjaxResult.success(adminSubmissionService.approveSubmission(submissionId, SecurityUtils.getUserId()));
    }

    private AdminSubmissionService.MutateSubmissionRequest toMutationRequest(MutateSubmissionPayload payload, long userId) {
        return new AdminSubmissionService.MutateSubmissionRequest(
            payload.certId(),
            payload.productType(),
            payload.vintageClassification(),
            payload.merchDescription(),
            payload.cardCategory(),
            payload.cardName(),
            payload.movieName(),
            payload.releaseYear(),
            payload.productionCompany(),
            payload.filmType(),
            payload.sportsType(),
            payload.groupName(),
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
            payload.entryNotes(),
            userId
        );
    }

    public record GenerateCertIdResponse(String certId) {
    }

    public record MutateSubmissionPayload(
        @NotBlank String certId,
        String productType,
        String vintageClassification,
        @Size(max = 4000) String merchDescription,
        String cardCategory,
        String cardName,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
        String yearLabel,
        String brandName,
        String playerName,
        String varietyName,
        String setName,
        String cardNumber,
        String languageCode,
        @Min(1) Integer populationValue,
        @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal centeringScore,
        @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal edgesScore,
        @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal cornersScore,
        @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal surfaceScore,
        String entryNotes
    ) {
    }

    public record ScorePayload(
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal centeringScore,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal edgesScore,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal cornersScore,
        @NotNull @DecimalMin("1.0") @DecimalMax("10.0") BigDecimal surfaceScore
    ) {
    }

    public record PopulationPayload(
        String productType,
        String cardCategory,
        String cardName,
        String setName,
        String cardNumber,
        String languageCode,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
        String vintageClassification,
        String finalGradeLabel,
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore,
        Long currentSubmissionId
    ) {
    }

    public record MatchCardPayload(String productType, String cardCategory, String setName, String cardNumber) {
    }

    public record BatchApprovePayload(List<Long> submissionIds) {
    }
}
