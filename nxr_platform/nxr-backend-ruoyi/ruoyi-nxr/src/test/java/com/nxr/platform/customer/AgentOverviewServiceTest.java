package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.commerce.CommercePolicyService;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentOverviewServiceTest {
    private JdbcTemplate jdbc;
    private AgentOperatorScopeService scope;
    private AgentOverviewService service;
    private AnnotationConfigApplicationContext context;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:agent_overview_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("agent_operator_h2.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("agent_overview_h2.sql"));
        }
        JdbcClient client = JdbcClient.create(jdbc);
        scope = new AgentOperatorScopeService(client, new OrderAccessScopeService(client, mock(CommercePolicyService.class)));
        service = new AgentOverviewService(client, scope);
        scope.saveBinding(1, 10, new AgentOperatorScopeService.BindingRequest(101L, true));
        jdbc.update("INSERT INTO sys_user_role VALUES(30,1)");
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"clients", "intakes", "batches", "returns", "wallet", "addresses"})
    void everyViewIncludesInactiveCompaniesAndCanFilterWithoutEmails(String view) throws Exception {
        var all = service.overview(1, view, null, null, 1, 20);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.items()).extracting(AgentOverviewService.OverviewRow::companyId).containsExactlyInAnyOrder(101L, 202L, 404L);
        assertThat(all.items()).filteredOn(row -> row.companyId() == 404).singleElement().satisfies(row -> {
            assertThat(row.companyActive()).isFalse();
            assertThat(row.companyName()).isEqualTo("Disabled merchant");
        });
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(all)).doesNotContain("email", "@example.test");
        var company = service.overview(1, view, 101L, null, 1, 20);
        assertThat(company.total()).isEqualTo(1);
        assertThat(company.items()).singleElement().satisfies(row -> {
            assertThat(row.companyName()).isEqualTo("Company A");
            assertThat(row.companyActive()).isTrue();
        });
        assertThat(service.overview(1, view, 303L, null, 1, 20).total()).isZero();
        assertThat(service.overview(1, view, 999L, null, 1, 20).items()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"clients,A_001", "intakes,IN-A", "batches,Alice batch", "returns,OUT-A", "wallet,USD", "addresses,One Road"})
    void eachViewSearchesItsOwnReferenceAndCompanyWithAccurateTotals(String view, String term) {
        var found = service.overview(1, view, null, term, 1, 20);
        assertThat(found.total()).isEqualTo(1);
        assertThat(found.items()).singleElement().extracting(AgentOverviewService.OverviewRow::companyId).isEqualTo(101L);
        assertThat(service.overview(1, view, null, "COMPANY B", 1, 20).items())
            .singleElement().extracting(AgentOverviewService.OverviewRow::companyId).isEqualTo(202L);
        assertThat(service.overview(1, view, 202L, term, 1, 20).total()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"clients", "intakes", "batches", "returns", "wallet", "addresses"})
    void tiedTimestampsPaginateDeterministicallyAndStayBounded(String view) {
        var all = service.overview(1, view, null, null, 1, 20).items();
        for (int page = 1; page <= all.size(); page++) {
            var result = service.overview(1, view, null, null, page, 1);
            assertThat(result.total()).isEqualTo(3);
            assertThat(result.items()).containsExactly(all.get(page - 1));
        }
        var beyond = service.overview(1, view, null, null, Integer.MAX_VALUE, 100);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(3);
        assertThat(service.overview(1, view, null, null, 0, 999).pageSize()).isEqualTo(100);
        assertThat(service.overview(1, view, null, null, 0, -1).page()).isEqualTo(1);
    }

    @Test void projectionsRetainUsefulCountsMoneyStatusAndAddress() {
        assertThat(first("clients").cardCount()).isEqualTo(2);
        assertThat(first("intakes").cardCount()).isEqualTo(2);
        assertThat(first("batches").cardCount()).isEqualTo(2);
        assertThat(first("returns").cardCount()).isEqualTo(2);
        assertThat(first("returns").statusCode()).isEqualTo("delivered");
        assertThat(first("returns").updatedAt().toString()).isEqualTo("2026-09-14T10:00");
        assertThat(first("wallet").amount()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(first("wallet").currencyCode()).isEqualTo("USD");
        assertThat(first("addresses").detail()).isEqualTo("One Road, Shanghai, 100001, CN");
        assertThat(first("addresses").statusCode()).isEqualTo("default");
        jdbc.update("UPDATE merchant_company_profile SET company_name='  ' WHERE customer_id=101");
        assertThat(first("clients").companyName()).isEqualTo("Merchant A");
    }

    @Test void relatedRowsAlwaysMatchTheirOwnerEvenWithDamagedHistoricalLinks() {
        jdbc.update("INSERT INTO agent_card VALUES(100,202,11,21)");
        jdbc.update("INSERT INTO merchant_order_batch_item VALUES(100,41,52)");
        assertThat(first("clients").cardCount()).isEqualTo(2);
        assertThat(first("returns").cardCount()).isEqualTo(2);
        assertThat(first("batches").cardCount()).isEqualTo(2);
        jdbc.update("UPDATE agent_intake SET client_id=2 WHERE id=11");
        jdbc.update("UPDATE agent_return_shipment SET client_id=2 WHERE id=21");
        assertThat(service.overview(1, "intakes", 101L, null, 1, 20).total()).isZero();
        assertThat(service.overview(1, "returns", 101L, null, 1, 20).total()).isZero();
        assertThat(first("clients").cardCount()).isZero();
    }

    @Test void requestTextNeverBecomesSqlAndSearchWildcardsAreLiteral() {
        assertThatThrownBy(() -> service.overview(1, "clients;DROP TABLE agent_client", null, null, 1, 20)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.overview(1, "clients", -1L, null, 1, 20)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.overview(1, "clients", null, "a".repeat(256), 1, 20)).hasMessageContaining("400");
        assertThat(service.overview(1, "clients", null, "' OR 1=1 --", 1, 20).total()).isZero();
        assertThat(service.overview(1, "clients", null, "%", 1, 20).total()).isZero();
        assertThat(service.overview(1, "clients", null, "_", 1, 20).total()).isEqualTo(1);
        assertThat(service.overview(1, "clients", null, "!", 1, 20).total()).isZero();
    }

    @Test void disabledRevokedOrBoundAccountsFailBeforeReadingPrivateBusinessTables() {
        JdbcClient business = mock(JdbcClient.class);
        AgentOverviewService guarded = new AgentOverviewService(business, scope);
        jdbc.update("INSERT INTO sys_user_role VALUES(10,1)");
        for (String view : List.of("clients", "addresses", "wallet")) {
            assertThatThrownBy(() -> guarded.overview(10, view, null, null, 1, 20)).hasMessageContaining("403");
            assertThatThrownBy(() -> guarded.overview(40, view, null, null, 1, 20)).hasMessageContaining("403");
        }
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=30");
        assertThatThrownBy(() -> guarded.overview(30, "clients", null, null, 1, 20)).hasMessageContaining("403");
        jdbc.update("UPDATE sys_user SET status='0',del_flag='2' WHERE user_id=30");
        assertThatThrownBy(() -> guarded.overview(30, "addresses", null, null, 1, 20)).hasMessageContaining("403");
        jdbc.update("UPDATE sys_user SET del_flag='0' WHERE user_id=30");
        jdbc.update("UPDATE sys_menu SET status='1' WHERE perms='nxr:agent:manage'");
        assertThatThrownBy(() -> guarded.overview(30, "clients", null, null, 1, 20)).hasMessageContaining("403");
        jdbc.update("UPDATE sys_menu SET status='0' WHERE perms='nxr:agent:manage'");
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=1 AND menu_id=2141");
        assertThatThrownBy(() -> guarded.overview(30, "addresses", null, null, 1, 20)).hasMessageContaining("403");
        verifyNoInteractions(business);
    }

    @Test void walletRequiresLiveFinancePermissionInAdditionToManagerPermission() {
        assertThat(service.overview(30, "wallet", null, null, 1, 20).total()).isEqualTo(3);
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=1 AND menu_id=2100");
        assertThat(service.overview(30, "clients", null, null, 1, 20).total()).isEqualTo(3);
        JdbcClient business = mock(JdbcClient.class);
        var guarded = new AgentOverviewService(business, scope);
        assertThatThrownBy(() -> guarded.overview(30, "wallet", null, null, 1, 20)).hasMessageContaining("nxr:customer:finance");
        verifyNoInteractions(business);
    }

    @Test void backendSessionPermissionAndLiveGuardApplyEvenWhenCompanyHeaderIsSpoofed() throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.register(AgentAdminSecurityTest.MethodSecurity.class);
        context.registerBean(AgentOverviewController.class, () -> new AgentOverviewController(service));
        context.refresh();
        var controller = context.getBean(AgentOverviewController.class);
        assertThatThrownBy(() -> controller.overview("clients", null, null, 1, 20)).isInstanceOf(AccessDeniedException.class);
        login(10, Set.of("nxr:agent:workbench"));
        assertThatThrownBy(() -> controller.overview("clients", null, null, 1, 20)).isInstanceOf(AccessDeniedException.class);
        login(10, Set.of("*:*:*"));
        assertThatThrownBy(() -> controller.overview("addresses", null, null, 1, 20)).hasMessageContaining("403");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/admin/agent/overview/addresses").header("X-NXR-Agent-Id", "202"))
            .andExpect(status().isForbidden());
        login(30, Set.of("nxr:agent:manage", "nxr:customer:finance"));
        mvc.perform(get("/api/admin/agent/overview/clients").header("X-NXR-Agent-Id", "202"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.total").value(3));
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=1 AND menu_id=2141");
        mvc.perform(get("/api/admin/agent/overview/clients").header("X-NXR-Agent-Id", "101"))
            .andExpect(status().isForbidden());
    }

    private AgentOverviewService.OverviewRow first(String view) {
        return service.overview(1, view, 101L, null, 1, 20).items().get(0);
    }

    private void login(long userId, Set<String> permissions) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName("test-" + userId);
        LoginUser login = new LoginUser(userId, 1L, user, permissions);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, "unused", List.of()));
    }
}
