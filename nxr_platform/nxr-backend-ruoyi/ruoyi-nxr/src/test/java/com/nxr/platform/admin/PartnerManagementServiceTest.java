package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.commerce.CommercePolicyService;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.customer.AgentOperatorScopeService;
import com.nxr.platform.customer.MerchantBatchService;
import com.nxr.platform.customer.MerchantWalletService;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class PartnerManagementServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private PartnerManagementService service;
    private AgentOperatorScopeService scope;
    private MerchantWalletService wallet;
    private ObjectMapper json;
    private ValidatorFactory validation;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:partner_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc=new JdbcTemplate(ds);try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("partner_management_h2.sql"));}
        JdbcClient client=JdbcClient.create(jdbc);wallet=new MerchantWalletService(client);
        scope=new AgentOperatorScopeService(client,new OrderAccessScopeService(client,mock(CommercePolicyService.class)));
        var batches=mock(MerchantBatchService.class);when(batches.listMerchantBatches(anyLong(),anyInt(),anyInt())).thenReturn(new MerchantBatchService.BatchPage(List.of(),0,1,20));
        transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        json=new ObjectMapper().findAndRegisterModules();validation=Validation.buildDefaultValidatorFactory();
        service=new PartnerManagementService(client,jdbc,scope,wallet,batches,json,validation.getValidator());
    }
    @AfterEach void close(){validation.close();}

    @Test void provisionCreatesSeparateMerchantAndBackendIdentitiesOnlyWithThePartnerRole() throws Exception {
        var request=request("open-a","partner_a","a-new@example.test",null,"BackendPass1");
        var result=open(1,request);
        assertThat(result.replayed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT pwd_update_date FROM sys_user WHERE user_id=?",java.sql.Timestamp.class,result.sysUserId())).isNull();
        assertThat(jdbc.queryForObject("SELECT account_type_code FROM customer_account WHERE id=?",String.class,result.customerId())).isEqualTo("merchant");
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?",Long.class,result.sysUserId())).containsExactly(10L);
        assertThat(scope.requireMerchant(result.sysUserId(),null)).isEqualTo(result.customerId());
        String customerHash=jdbc.queryForObject("SELECT password_hash FROM customer_account WHERE id=?",String.class,result.customerId());
        String backendHash=jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=?",String.class,result.sysUserId());
        assertThat(SecurityUtils.matchesPassword("BackendPass1",backendHash)).isTrue();
        assertThat(SecurityUtils.matchesPassword("BackendPass1",customerHash)).isFalse();
        assertThat(count("customer_session")).isZero();
        assertThat(json.writeValueAsString(request)).doesNotContain("BackendPass1").doesNotContain("password");
        assertThat(request.toString()).doesNotContain("BackendPass1");
        assertThat(json.writeValueAsString(result)).doesNotContain("password").doesNotContain(backendHash);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM partner_management_command",String.class)).matches("[a-f0-9]{64}");
    }
    @Test void openingRetriesAreIdempotentButChangedCredentialsOrPayloadCannotReuseTheCommand() {
        var request=request("same","partner_a","new@example.test",null,"BackendPass1");
        var first=open(1,request);var repeated=open(1,request);
        assertThat(repeated.customerId()).isEqualTo(first.customerId());assertThat(repeated.sysUserId()).isEqualTo(first.sysUserId());assertThat(repeated.replayed()).isTrue();
        assertThatThrownBy(()->open(1,request("same","partner_a","new@example.test",null,"DifferentPass2"))).hasMessageContaining("different credentials");
        assertThatThrownBy(()->open(1,request("same","partner_b","other@example.test",null,"BackendPass1"))).hasMessageContaining("different details");
        assertThat(count("partner_management_command")).isEqualTo(1);
    }
    @Test void existingMerchantMustBeExplicitAndConflictingEmailOrUsernameNeverBindsAnotherAccount() {
        assertThatThrownBy(()->open(1,request("email-conflict","partner_a","a@example.test",null,"BackendPass1"))).hasMessageContaining("Select the existing merchant explicitly");
        assertThatThrownBy(()->open(1,request("username-conflict","ADMIN","new@example.test",null,"BackendPass1"))).hasMessageContaining("username is already in use");
        assertThatThrownBy(()->open(1,request("wrong-email","partner_a","b@example.test",101L,"BackendPass1"))).hasMessageContaining("does not match");
        assertThatThrownBy(()->open(1,request("normal-customer","partner_a",null,303L,"BackendPass1"))).hasMessageContaining("404");
        long before=count("customer_account");var existing=open(1,request("existing","partner_existing",null,101L,"BackendPass1"));
        assertThat(existing.customerId()).isEqualTo(101);assertThat(count("customer_account")).isEqualTo(before);
        assertThat(scope.requireMerchant(existing.sysUserId(),null)).isEqualTo(101);
    }
    @Test void failuresAfterAccountInsertionRollbackCompanyProfileBackendRolesBindingAndCommand() {
        jdbc.execute("ALTER TABLE partner_management_command ADD CONSTRAINT reject_provision CHECK(operation_code<>'provision')");
        long customers=count("customer_account"),users=count("sys_user"),profiles=count("merchant_company_profile");
        assertThatThrownBy(()->open(1,request("rollback","partner_fail","rollback@example.test",null,"BackendPass1")))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("customer_account")).isEqualTo(customers);assertThat(count("sys_user")).isEqualTo(users);
        assertThat(count("merchant_company_profile")).isEqualTo(profiles);assertThat(count("agent_operator_binding")).isZero();assertThat(count("agent_operator_event")).isZero();
    }
    @Test void concurrentSameUsernameProvisioningCreatesOnlyOneBackendIdentity() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{try{open(1,request("one","same_name","one@example.test",null,"BackendPass1"));return true;}catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);return false;}});
            var two=pool.submit(()->{try{open(80,request("two","same_name","two@example.test",null,"BackendPass1"));return true;}catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);return false;}});
            assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_name='same_name'",Long.class)).isEqualTo(1);
        assertThat(count("partner_management_command")).isEqualTo(1);
    }
    @Test void offlineRechargeRemainsPendingUntilExistingFinanceConfirmationAndRetriesNeverCreditTwice() {
        var request=new PartnerManagementService.RechargeRequest("deposit","USD",new BigDecimal("120.00"),"bank_transfer","bank-ref","proof");
        var recharge=transaction.execute(s->service.registerRecharge(1,101,request));
        assertThat(recharge.statusCode()).isEqualTo("pending");assertThat(wallet.listWallets(101)).isEmpty();assertThat(count("merchant_wallet_transaction")).isZero();
        assertThat(transaction.execute(s->service.registerRecharge(1,101,request)).id()).isEqualTo(recharge.id());assertThat(count("merchant_wallet_recharge")).isEqualTo(1);
        transaction.execute(s->wallet.reviewRecharge(101,recharge.id(),1,true,new MerchantWalletService.RechargeReviewRequest("bank-verified","Finance verified")));
        assertThat(transaction.execute(s->service.registerRecharge(1,101,request)).statusCode()).isEqualTo("confirmed");
        assertThat(wallet.listWallets(101)).singleElement().satisfies(w->assertThat(w.balance()).isEqualByComparingTo("120.00"));assertThat(count("merchant_wallet_transaction")).isEqualTo(1);
        assertThatThrownBy(()->transaction.execute(s->service.registerRecharge(1,101,new PartnerManagementService.RechargeRequest("deposit","USD",BigDecimal.TEN,"bank_transfer","bank-ref","proof")))).hasMessageContaining("different details");
        assertThatThrownBy(()->transaction.execute(s->service.registerRecharge(1,101,new PartnerManagementService.RechargeRequest("online","USD",BigDecimal.TEN,"stripe",null,null)))).hasMessageContaining("offline transfer");
    }
    @Test void listSummarizesCustodyAndActiveAccountsButHidesFinancialDataWithoutFinancePermission() {
        var partner=open(1,request("operator","partner_a",null,101L,"BackendPass1"));
        jdbc.update("INSERT INTO agent_client VALUES(1,101),(2,101)");jdbc.update("INSERT INTO agent_card VALUES(1,101,'in_stock'),(2,101,'returned'),(3,101,'submitted'),(4,101,'delivered')");
        jdbc.update("INSERT INTO merchant_order_batch VALUES(1,101,'open'),(2,101,'delivered')");
        jdbc.update("INSERT INTO merchant_wallet(customer_id,currency_code,balance) VALUES(101,'USD',50),(101,'EUR',90)");
        var summary=service.list(1,1,20,"partner_a",null,"EUR").items().get(0);
        assertThat(summary.id()).isEqualTo(101);assertThat(summary.clientCount()).isEqualTo(2);assertThat(summary.inventoryCount()).isEqualTo(2);assertThat(summary.pendingBatchCount()).isEqualTo(1);
        assertThat(summary.operatorAccounts()).contains("partner_a");assertThat(summary.walletBalance()).isEqualByComparingTo("90.00");
        jdbc.update("UPDATE merchant_company_profile SET contact_name='Regional Contact' WHERE customer_id=101");
        assertThat(service.list(1,1,20,"Regional",null,"USD").items()).extracting(PartnerManagementService.PartnerSummary::id).containsExactly(101L);
        var limited=service.detail(70,101,"USD");assertThat(limited.financeVisible()).isFalse();assertThat(limited.partner().walletBalance()).isNull();assertThat(limited.partner().pendingRechargeCount()).isNull();
        assertThat(limited.wallets()).isEmpty();assertThat(limited.recharges().items()).isEmpty();
        assertThatThrownBy(()->service.detail(partner.sysUserId(),101,"USD")).hasMessageContaining("cannot access platform");
    }
    @Test void profileDeactivationPreservesHistoryRevokesCustomerSessionsAndCanBeReenabled() {
        var partner=open(1,request("operator","partner_a",null,101L,"BackendPass1"));
        jdbc.update("INSERT INTO customer_session(customer_id) VALUES(101)");
        var inactive=transaction.execute(s->service.update(1,101,new PartnerManagementService.UpdateRequest("Merchant A","a@example.test","123","Company A","Contact",false)));
        assertThat(inactive.active()).isFalse();assertThat(inactive.activeOperatorCount()).isZero();assertThat(count("customer_session")).isZero();
        assertThat(service.detail(1,101,"USD").operators()).hasSize(1);
        assertThatThrownBy(()->scope.requireMerchant(partner.sysUserId(),null)).hasMessageContaining("active merchant");
        transaction.execute(s->service.update(1,101,new PartnerManagementService.UpdateRequest("Merchant A","a@example.test","123","Company A","Contact",true)));
        assertThat(scope.requireMerchant(partner.sysUserId(),null)).isEqualTo(101);
    }
    @Test void actionPermissionsAreIndependentAndScopedStaffCannotUsePlatformManagement() {
        assertThatThrownBy(()->open(70,request("x","partner_a","new@example.test",null,"BackendPass1"))).hasMessageContaining("Required platform permission");
        assertThatThrownBy(()->service.list(40,1,20,null,null,"USD")).hasMessageContaining("unrestricted");
        assertThatThrownBy(()->transaction.execute(s->service.registerRecharge(80,101,new PartnerManagementService.RechargeRequest("x","USD",BigDecimal.TEN,"bank_transfer",null,null)))).hasMessageContaining("nxr:customer:finance");
        var opened=open(80,request("allowed","partner_allowed","allowed@example.test",null,"BackendPass1"));assertThat(opened.customerId()).isPositive();
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=60 AND menu_id=100");
        assertThatThrownBy(()->open(80,request("no-user-permission","partner_other","other@example.test",null,"BackendPass1"))).hasMessageContaining("system:user:add");
    }
    @Test void invalidLoginCredentialsAndMarkupAreRejectedBeforeAnyAccountIsCreated() {
        long users=count("sys_user");
        assertThatThrownBy(()->open(1,request("short","a","a-new@example.test",null,"BackendPass1"))).hasMessageContaining("2 to 20");
        assertThatThrownBy(()->open(1,request("long-password","partner_a","a-new@example.test",null,"123456789012345678901"))).hasMessageContaining("5 to 20");
        assertThatThrownBy(()->open(1,request("markup","<script>a</script>","a-new@example.test",null,"BackendPass1"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(count("sys_user")).isEqualTo(users);
    }
    private PartnerManagementService.ProvisionRequest request(String key,String user,String email,Long existing,String password){
        return new PartnerManagementService.ProvisionRequest(key,existing,email,existing==null?"New partner":null,null,existing==null?"New Company":null,existing==null?"Contact":null,user,user,password);
    }
    private PartnerManagementService.ProvisionResult open(long actor,PartnerManagementService.ProvisionRequest request){return transaction.execute(s->service.provision(actor,request));}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
}
