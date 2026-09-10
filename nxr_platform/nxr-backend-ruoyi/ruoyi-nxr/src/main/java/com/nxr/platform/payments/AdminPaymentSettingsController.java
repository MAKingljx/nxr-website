package com.nxr.platform.payments;

import com.nxr.platform.payments.PaymentModels.ConfigurationUpdate;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payment-settings")
public class AdminPaymentSettingsController {

    private final PaymentConfigurationService configurations;

    public AdminPaymentSettingsController(PaymentConfigurationService configurations) {
        this.configurations = configurations;
    }

    @PreAuthorize("@ss.hasPermi('nxr:payment:config')")
    @GetMapping
    public AjaxResult list() {
        return AjaxResult.success(configurations.listAdminConfigurations());
    }

    @PreAuthorize("@ss.hasPermi('nxr:payment:config')")
    @Log(
        title = "支付渠道设置",
        businessType = BusinessType.UPDATE,
        isSaveRequestData = false,
        isSaveResponseData = false
    )
    @PutMapping("/{provider}")
    public AjaxResult update(@PathVariable String provider, @RequestBody ConfigurationUpdate request) {
        return AjaxResult.success(configurations.update(provider, request, SecurityUtils.getUserId()));
    }
}
