package com.nxr.platform.admin;

import com.nxr.platform.customer.EnterpriseCreditService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/enterprise-credit")
public class AdminEnterpriseCreditController {
    private final EnterpriseCreditService credits;
    public AdminEnterpriseCreditController(EnterpriseCreditService credits) { this.credits=credits; }

    @PreAuthorize("@ss.hasPermi('nxr:credit:config')")
    @GetMapping("/settings")
    public AjaxResult settings() { return AjaxResult.success(credits.adminSettings(SecurityUtils.getUserId())); }

    @PreAuthorize("@ss.hasPermi('nxr:credit:config')")
    @Log(title="Enterprise credit settings",businessType=BusinessType.UPDATE)
    @PutMapping("/settings")
    public AjaxResult settings(@RequestBody EnterpriseCreditService.SettingsRequest request) {
        return AjaxResult.success(credits.saveSettings(SecurityUtils.getUserId(),request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @GetMapping("/companies/{customerId}")
    public AjaxResult summary(@PathVariable long customerId) {
        credits.requireFinance(SecurityUtils.getUserId()); return AjaxResult.success(credits.summary(customerId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @PostMapping("/companies/{customerId}/quote")
    public AjaxResult quote(@PathVariable long customerId,@RequestBody EnterpriseCreditService.QuoteRequest request) {
        credits.requireFinance(SecurityUtils.getUserId()); return AjaxResult.success(credits.quote(customerId,request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @PostMapping("/companies/{customerId}/conversion-preview")
    public AjaxResult preview(@PathVariable long customerId) {
        return AjaxResult.success(credits.conversionPreview(SecurityUtils.getUserId(),customerId));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @Log(title="Legacy enterprise credit conversion",businessType=BusinessType.UPDATE)
    @PostMapping("/companies/{customerId}/convert")
    public AjaxResult convert(@PathVariable long customerId,@RequestBody EnterpriseCreditService.ConversionRequest request) {
        return AjaxResult.success(credits.convert(SecurityUtils.getUserId(),customerId,request));
    }

    @PreAuthorize("@ss.hasPermi('nxr:customer:finance')")
    @Log(title="Legacy recharge points quote",businessType=BusinessType.UPDATE)
    @PostMapping("/companies/{customerId}/recharges/{rechargeId}/quote")
    public AjaxResult pinQuote(@PathVariable long customerId,@PathVariable long rechargeId,
        @RequestBody EnterpriseCreditService.VersionRequest request) {
        return AjaxResult.success(credits.quoteLegacyRecharge(SecurityUtils.getUserId(),customerId,rechargeId,request));
    }
}
