package com.nxr.platform.customer;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.nxr.platform.customer.PartnerReturnService.*;

@RestController
@RequestMapping("/api/admin/partner-returns")
@PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping,nxr:order:batch,nxr:order:workbench')")
public class PartnerReturnController {
    private final PartnerReturnService service;
    public PartnerReturnController(PartnerReturnService service){this.service=service;}
    @ModelAttribute public void privateResponses(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping("/companies") public AjaxResult companies(@RequestParam(required=false) String query){return AjaxResult.success(service.companies(SecurityUtils.getUserId(),query));}
    @GetMapping("/cards") public AjaxResult cards(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) Long companyId,@RequestParam(required=false) Long orderId,@RequestParam(required=false) Long batchId,@RequestParam(required=false) String query){return AjaxResult.success(service.cards(SecurityUtils.getUserId(),page,pageSize,companyId,orderId,batchId,query));}
    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @PostMapping("/cards/{id}/check") @Log(title="卡片回寄核对",businessType=BusinessType.UPDATE)
    public AjaxResult check(@PathVariable long id,@RequestBody CheckRequest request){return AjaxResult.success(service.check(SecurityUtils.getUserId(),id,request));}
    @GetMapping public AjaxResult parcels(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) Long companyId,@RequestParam(required=false) Long orderId,@RequestParam(required=false) Long batchId,@RequestParam(required=false) String statusCode,@RequestParam(required=false) String query){return AjaxResult.success(service.adminParcels(SecurityUtils.getUserId(),page,pageSize,companyId,orderId,batchId,statusCode,query));}
    @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.adminDetail(SecurityUtils.getUserId(),id));}
    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @PostMapping @Log(title="代理卡片回寄",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestBody CreateRequest request){return AjaxResult.success(service.create(SecurityUtils.getUserId(),request));}
    @PreAuthorize("@ss.hasAnyPermi('nxr:order:manage,nxr:order:shipping')")
    @PostMapping("/{id}/delivered") @Log(title="代理回寄签收",businessType=BusinessType.UPDATE)
    public AjaxResult delivered(@PathVariable long id){return AjaxResult.success(service.delivered(SecurityUtils.getUserId(),id));}
}
