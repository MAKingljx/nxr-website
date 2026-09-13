package com.nxr.platform.admin;

import com.nxr.platform.customer.AgentWorkbenchService;
import com.nxr.platform.customer.MerchantWalletService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Platform-only partner lifecycle. Recharge approval remains in the existing finance controller. */
@RestController
@RequestMapping("/api/admin/partners")
public class PartnerManagementController {
    private final PartnerManagementService service;
    public PartnerManagementController(PartnerManagementService service) {this.service=service;}
    @GetMapping
    @PreAuthorize("@ss.hasPermi('nxr:partner:list')")
    public AgentWorkbenchService.Page<PartnerManagementService.PartnerSummary> list(@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String query,@RequestParam(required=false) Boolean active,
        @RequestParam(defaultValue="USD") String currencyCode) {return service.list(SecurityUtils.getUserId(),page,pageSize,query,active,currencyCode);}
    @GetMapping("/{customerId}")
    @PreAuthorize("@ss.hasPermi('nxr:partner:list')")
    public PartnerManagementService.Detail detail(@PathVariable long customerId,@RequestParam(defaultValue="USD") String currencyCode) {
        return service.detail(SecurityUtils.getUserId(),customerId,currencyCode);
    }
    @PostMapping
    @PreAuthorize("@ss.hasPermi('nxr:partner:manage') and @ss.hasPermi('system:user:add') and @ss.hasPermi('nxr:customer:manage')")
    @Log(title="子代理开通",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public PartnerManagementService.ProvisionResult provision(@RequestBody PartnerManagementService.ProvisionRequest request) {
        return service.provision(SecurityUtils.getUserId(),request);
    }
    @PutMapping("/{customerId}")
    @PreAuthorize("@ss.hasPermi('nxr:partner:manage') and @ss.hasPermi('nxr:customer:manage')")
    @Log(title="子代理资料",businessType=BusinessType.UPDATE)
    public PartnerManagementService.PartnerSummary update(@PathVariable long customerId,@RequestBody PartnerManagementService.UpdateRequest request) {
        return service.update(SecurityUtils.getUserId(),customerId,request);
    }
    @PostMapping("/{customerId}/wallet-recharges")
    @PreAuthorize("@ss.hasPermi('nxr:partner:manage') and @ss.hasPermi('nxr:customer:finance')")
    @Log(title="子代理线下充值登记",businessType=BusinessType.INSERT)
    public MerchantWalletService.RechargeRecord recharge(@PathVariable long customerId,@RequestBody PartnerManagementService.RechargeRequest request) {
        return service.registerRecharge(SecurityUtils.getUserId(),customerId,request);
    }
}
