package com.bustix.dashboard;

import com.bustix.tenant.TenantContext;
import com.bustix.user.CurrentUserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only landing-page overviews, one per role. Cross-cutting by nature
 * (each pulls from bookings + trips + cargo + fleet), so it lives in its own
 * package rather than any one domain controller - the aggregation lives in
 * {@link DashboardService}, the returned records are purpose-built read
 * shapes, same convention as {@code TripSearchResult}/{@code WaybillWithItems}.
 *
 * Scoping mirrors the rest of the API:
 *  - operator/agent: tenant-scoped via {@link TenantContext#require()}
 *  - platform_admin: cross-tenant, never touches TenantContext
 *  - customer: ownership-scoped via the resolved internal user id
 *
 * No new {@code SecurityConfig} matcher is needed - the blanket
 * {@code /api/**}.authenticated() rule covers these; {@code @PreAuthorize}
 * does the per-role gate (method security is enabled, see SecurityConfig).
 */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;
    private final CurrentUserService currentUserService;

    public DashboardController(DashboardService dashboardService, CurrentUserService currentUserService) {
        this.dashboardService = dashboardService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/operator/dashboard")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public OperatorDashboard operator(@RequestParam(defaultValue = "30d") String period) {
        return dashboardService.operatorDashboard(TenantContext.require(), DashboardPeriod.parse(period));
    }

    @GetMapping("/api/agent/dashboard")
    @PreAuthorize("hasRole('AGENT')")
    public AgentDashboard agent(@AuthenticationPrincipal Jwt jwt) {
        return dashboardService.agentDashboard(TenantContext.require(), currentUserService.resolveInternalUserId(jwt));
    }

    @GetMapping("/api/platform/dashboard")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public PlatformDashboard platform(@RequestParam(defaultValue = "30d") String period) {
        return dashboardService.platformDashboard(DashboardPeriod.parse(period));
    }

    @GetMapping("/api/my-dashboard")
    @PreAuthorize("hasRole('CUSTOMER')")
    public CustomerDashboard customer(@AuthenticationPrincipal Jwt jwt) {
        return dashboardService.customerDashboard(currentUserService.resolveInternalUserId(jwt));
    }
}
