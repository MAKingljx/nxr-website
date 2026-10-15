package com.nxr.platform.customer;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/agent")
@PreAuthorize("@ss.hasAnyPermi('nxr:agent:workbench,nxr:agent:manage')")
public class AgentOrderController {
    private static final String COMPANY="X-NXR-Agent-Id";
    private final AgentOperatorScopeService scope;
    private final AgentOrderService service;
    public AgentOrderController(AgentOperatorScopeService scope,AgentOrderService service) { this.scope=scope; this.service=service; }

    @ModelAttribute
    public void privateResponses(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        response.addHeader("Vary",COMPANY);
    }
    @GetMapping("/workbench-order-options")
    public AgentOrderService.Options options(@RequestHeader(name=COMPANY,required=false) Long company) {
        return service.options(scope.currentMerchant(company));
    }
    @GetMapping("/workbench-orders")
    public AgentWorkbenchService.Page<AgentOrderService.WorkbenchOrder> list(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
        @RequestParam(required=false) String query,@RequestParam(required=false) String statusCode) {
        return service.list(scope.currentMerchant(company),page,pageSize,query,statusCode);
    }
    @GetMapping("/workbench-orders/{id}")
    public AgentOrderService.Detail detail(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable long id) {
        return service.detail(scope.currentMerchant(company),id);
    }
    @PostMapping("/workbench-orders")
    public AgentOrderService.Detail create(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestBody AgentOrderService.CreateRequest request) {
        return service.create(scope.currentMerchant(company),request);
    }
}
