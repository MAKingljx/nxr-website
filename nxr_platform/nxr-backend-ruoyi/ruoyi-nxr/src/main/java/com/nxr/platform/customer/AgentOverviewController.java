package com.nxr.platform.customer;

import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/agent/overview")
@PreAuthorize("@ss.hasPermi('nxr:agent:manage')")
public class AgentOverviewController {
    private final AgentOverviewService service;

    public AgentOverviewController(AgentOverviewService service) {
        this.service = service;
    }

    @ModelAttribute
    public void privateResponses(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    // Agent company headers never supply authorization for these platform-only views.
    @GetMapping("/{view}")
    public AgentWorkbenchService.Page<AgentOverviewService.OverviewRow> overview(@PathVariable String view,
        @RequestParam(required = false) Long companyId, @RequestParam(required = false) String query,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.overview(SecurityUtils.getUserId(), view, companyId, query, page, pageSize);
    }
}
