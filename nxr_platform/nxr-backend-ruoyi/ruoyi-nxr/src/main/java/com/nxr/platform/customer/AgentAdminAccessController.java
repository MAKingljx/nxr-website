package com.nxr.platform.customer;

import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/agent")
@PreAuthorize("@ss.hasAnyPermi('nxr:agent:workbench,nxr:agent:manage')")
public class AgentAdminAccessController {
    private final AgentOperatorScopeService scope;
    public AgentAdminAccessController(AgentOperatorScopeService scope) { this.scope=scope; }
    @GetMapping("/context")
    public AgentOperatorScopeService.Context context(@RequestHeader(name="X-NXR-Agent-Id",required=false) Long company) {
        return scope.currentContext(company);
    }
    @GetMapping("/companies")
    public AgentWorkbenchService.Page<AgentOperatorScopeService.Company> companies(@RequestParam(required=false) String query,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return scope.companies(SecurityUtils.getUserId(),query,page,pageSize);
    }
    @GetMapping("/operators")
    @PreAuthorize("@ss.hasPermi('nxr:agent:manage')")
    public AgentWorkbenchService.Page<AgentOperatorScopeService.Operator> operators(@RequestParam(required=false) String query,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return scope.operators(SecurityUtils.getUserId(),query,page,pageSize);
    }
    @GetMapping("/operator-candidates")
    @PreAuthorize("@ss.hasPermi('nxr:agent:manage')")
    public AgentWorkbenchService.Page<AgentOperatorScopeService.Candidate> candidates(@RequestParam(required=false) String query,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return scope.candidates(SecurityUtils.getUserId(),query,page,pageSize);
    }
    @PutMapping("/operators/{sysUserId}")
    @PreAuthorize("@ss.hasPermi('nxr:agent:manage')")
    public AgentOperatorScopeService.Operator save(@PathVariable long sysUserId,@RequestBody AgentOperatorScopeService.BindingRequest request) {
        return scope.saveBinding(SecurityUtils.getUserId(),sysUserId,request);
    }
}
