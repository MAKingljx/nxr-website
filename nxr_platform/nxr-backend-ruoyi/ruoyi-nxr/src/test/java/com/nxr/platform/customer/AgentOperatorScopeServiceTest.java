package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nxr.platform.commerce.CommercePolicyService;
import com.nxr.platform.commerce.OrderAccessScopeService;
import java.sql.Connection;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class AgentOperatorScopeServiceTest {
    private JdbcTemplate jdbc;
    private AgentOperatorScopeService scope;
    private TransactionTemplate transaction;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:agent_operator_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ClassPathResource("agent_operator_h2.sql")); }
        JdbcClient client=JdbcClient.create(jdbc);
        scope=new AgentOperatorScopeService(client,new OrderAccessScopeService(client,mock(CommercePolicyService.class)));
        transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        bind(10,101);bind(20,202);
    }
    @Test void ordinaryOperatorsCanOnlyResolveTheirLiveMerchantBinding() {
        assertThat(scope.context(10,null).platformManager()).isFalse();
        assertThat(scope.requireMerchant(10,null)).isEqualTo(101);
        assertThat(scope.requireMerchant(10,101L)).isEqualTo(101);
        assertThatThrownBy(()->scope.requireMerchant(10,202L)).hasMessageContaining("403");
        assertThatThrownBy(()->scope.requireMerchant(30,101L)).hasMessageContaining("403");
        assertThat(scope.companies(10,null,1,20).items()).extracting(AgentOperatorScopeService.Company::id).containsExactly(101L);
        assertThat(scope.companies(10,"Company B",1,20).items()).isEmpty();
        assertThatThrownBy(()->scope.operators(10,null,1,20)).hasMessageContaining("403");
        assertThatThrownBy(()->scope.candidates(10,null,1,20)).hasMessageContaining("403");
        assertThatThrownBy(()->transaction.execute(s->scope.saveBinding(10,30,new AgentOperatorScopeService.BindingRequest(101L,true)))).hasMessageContaining("403");
    }
    @Test void platformManagerMustHaveLivePermissionAndUnrestrictedScopeAndSelectCompanyExplicitly() {
        assertThat(scope.context(1,null).platformManager()).isTrue();assertThat(scope.context(1,null).company()).isNull();
        assertThat(scope.context(1,202L).company().id()).isEqualTo(202L);
        assertThatThrownBy(()->scope.requireMerchant(1,null)).hasMessageContaining("Select an agent company");
        assertThatThrownBy(()->scope.context(40,101L)).hasMessageContaining("unrestricted");
        assertThatThrownBy(()->scope.context(50,101L)).hasMessageContaining("403");
        assertThat(scope.companies(1,null,1,20).items()).extracting(AgentOperatorScopeService.Company::id).containsExactly(202L,101L);
    }
    @Test void disabledOrDeletedUserBindingCompanyAndRevokedRoleFailOnTheNextRequest() {
        jdbc.update("UPDATE agent_operator_binding SET active=0 WHERE sys_user_id=10");
        assertThatThrownBy(()->scope.requireMerchant(10,101L)).hasMessageContaining("disabled");
        jdbc.update("UPDATE agent_operator_binding SET active=1 WHERE sys_user_id=10");
        jdbc.update("UPDATE customer_account SET is_active=0 WHERE id=101");
        assertThatThrownBy(()->scope.requireMerchant(10,101L)).hasMessageContaining("active merchant");
        jdbc.update("UPDATE customer_account SET is_active=1,account_type_code='customer' WHERE id=101");
        assertThatThrownBy(()->scope.requireMerchant(10,101L)).hasMessageContaining("active merchant");
        jdbc.update("UPDATE customer_account SET account_type_code='merchant' WHERE id=101");
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=10");
        assertThatThrownBy(()->scope.requireMerchant(10,101L)).hasMessageContaining("active backend");
        jdbc.update("UPDATE sys_user SET status='0',del_flag='2' WHERE user_id=10");
        assertThatThrownBy(()->scope.requireMerchant(10,101L)).hasMessageContaining("active backend");
        jdbc.update("UPDATE sys_user SET del_flag='0' WHERE user_id=10");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=10 AND role_id=10");
        assertThatThrownBy(()->scope.requireMerchant(10,101L)).hasMessageContaining("isolated agent");
    }
    @Test void bindingGrantsOnlyDedicatedRoleAndDeactivationPreservesOtherRolesAndHistory() {
        var enabled=transaction.execute(s->scope.saveBinding(1,60,new AgentOperatorScopeService.BindingRequest(101L,true)));
        assertThat(enabled.active()).isTrue();
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=60 ORDER BY role_id",Long.class)).containsExactly(10L,40L);
        assertThat(scope.requireMerchant(60,null)).isEqualTo(101L);
        transaction.execute(s->scope.saveBinding(1,60,new AgentOperatorScopeService.BindingRequest(101L,false)));
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=60",Long.class)).containsExactly(40L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_operator_binding WHERE sys_user_id=60",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_operator_event WHERE sys_user_id=60",Long.class)).isEqualTo(2);
        assertThatThrownBy(()->scope.requireMerchant(60,null)).hasMessageContaining("disabled");
        assertThatThrownBy(()->transaction.execute(s->scope.saveBinding(1,60,new AgentOperatorScopeService.BindingRequest(202L,false)))).hasMessageContaining("cannot change");
    }
    @Test void privilegedTargetsOrdinaryCustomersAndContaminatedAgentRolesCannotBeBound() {
        assertThatThrownBy(()->bind(1,101)).hasMessageContaining("without platform");
        assertThatThrownBy(()->bind(50,101)).hasMessageContaining("without platform");
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=30");
        assertThatThrownBy(()->bind(50,101)).hasMessageContaining("without platform");
        assertThatThrownBy(()->bind(30,303)).hasMessageContaining("active merchant");
        assertThatThrownBy(()->bind(30,404)).hasMessageContaining("active merchant");
        assertThat(scope.candidates(1,null,1,100).items()).extracting(AgentOperatorScopeService.Candidate::sysUserId).contains(30L,60L).doesNotContain(1L,40L,50L);
        jdbc.update("INSERT INTO sys_role_menu VALUES(10,2100)");
        assertThatThrownBy(()->bind(30,101)).hasMessageContaining("platform permissions");
        assertThatThrownBy(()->scope.requireMerchant(10,101L)).hasMessageContaining("isolated agent");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_operator_binding WHERE sys_user_id=30",Long.class)).isZero();
    }
    @Test void assigningAnAdditionalPrivilegedRoleImmediatelyClosesAgentCompanyAccess() {
        jdbc.update("INSERT INTO sys_user_role VALUES(10,30)");
        assertThatThrownBy(()->scope.context(10,null)).hasMessageContaining("isolated agent");
        assertThatThrownBy(()->scope.companies(10,null,1,20)).hasMessageContaining("403");
    }
    @Test void administratorCanRevokeDisabledAccountOrRoleWithoutReactivatingThem() {
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=10");
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=10");
        transaction.execute(s->scope.saveBinding(1,10,new AgentOperatorScopeService.BindingRequest(null,false)));
        assertThat(jdbc.queryForObject("SELECT active FROM agent_operator_binding WHERE sys_user_id=10",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=10 AND role_id=10",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE user_id=10",String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_role WHERE role_id=10",String.class)).isEqualTo("1");
    }
    @Test void roleGrantAndBindingRollbackTogetherWhenBindingAuditFails() {
        jdbc.execute("ALTER TABLE agent_operator_event ADD CONSTRAINT reject_spare_operator CHECK(sys_user_id<>60)");
        assertThatThrownBy(()->bind(60,101)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_operator_binding WHERE sys_user_id=60",Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=60",Long.class)).containsExactly(40L);
    }
    private void bind(long user,long company) { transaction.execute(s->scope.saveBinding(1,user,new AgentOperatorScopeService.BindingRequest(company,true))); }
}
