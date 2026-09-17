package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nxr.platform.admission.OrderAdmissionService;
import com.nxr.platform.commerce.CommercePolicyService;
import com.nxr.platform.commerce.CustomerCommercePolicyController;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;

class AgentAdminSecurityTest {
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private AgentCommerceController commerceController;
    private AgentWorkbenchController workbenchController;
    private AgentAdminAccessController accessController;
    private AgentWorkbenchService workbench;
    private CustomerPortalService portal;
    private MerchantWalletService wallet;
    private MerchantBatchService batches;
    private CustomerOrderPhotoService photos;
    private OrderFulfillmentService fulfillment;
    private CommercePolicyService commerce;

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurity {
        @Bean(name="ss") PermissionProbe permissionProbe() { return new PermissionProbe(); }
    }
    public static class PermissionProbe {
        public boolean hasPermi(String permission) { return hasAnyPermi(permission); }
        public boolean hasAnyPermi(String permissions) {
            try {
                Set<String> actual=SecurityUtils.getLoginUser().getPermissions();
                return actual.contains("*:*:*")||Arrays.stream(permissions.split(",")).anyMatch(actual::contains);
            } catch(Exception unauthenticated) { return false; }
        }
    }
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:agent_security_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);try(Connection c=ds.getConnection()) {ScriptUtils.executeSqlScript(c,new ClassPathResource("agent_operator_h2.sql"));}
        commerce=mock(CommercePolicyService.class);
        AgentOperatorScopeService scope=new AgentOperatorScopeService(JdbcClient.create(jdbc),new OrderAccessScopeService(JdbcClient.create(jdbc),commerce));
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->scope.saveBinding(1,10,new AgentOperatorScopeService.BindingRequest(101L,true)));
        workbench=mock(AgentWorkbenchService.class);portal=mock(CustomerPortalService.class);wallet=mock(MerchantWalletService.class);
        batches=mock(MerchantBatchService.class);photos=mock(CustomerOrderPhotoService.class);fulfillment=mock(OrderFulfillmentService.class);
        context=new AnnotationConfigApplicationContext();context.register(MethodSecurity.class);
        context.registerBean(AgentWorkbenchController.class,()->new AgentWorkbenchController(scope,workbench));
        context.registerBean(AgentAdminAccessController.class,()->new AgentAdminAccessController(scope));
        context.registerBean(AgentCommerceController.class,()->new AgentCommerceController(scope,batches,portal,fulfillment,mock(OrderAdmissionService.class),wallet,photos,commerce));
        context.refresh();commerceController=context.getBean(AgentCommerceController.class);
        workbenchController=context.getBean(AgentWorkbenchController.class);accessController=context.getBean(AgentAdminAccessController.class);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext();if(context!=null)context.close(); }

    @Test void unauthenticatedAndUnpermissionedBackendSessionsCannotInvokeAgentOperations() {
        assertThatThrownBy(()->workbenchController.clients(null,1,20,null,null)).isInstanceOf(AccessDeniedException.class);
        login(30,Set.of());
        assertThatThrownBy(()->commerceController.wallets(101L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(wallet,workbench);
        // A stale/misissued permission alone never substitutes for an actual merchant binding.
        login(30,Set.of("nxr:agent:workbench"));
        assertThatThrownBy(()->commerceController.wallets(101L)).hasMessageContaining("403");
        verifyNoInteractions(wallet);
    }
    @Test void boundAgentCannotManageOperatorsOrSelectAnotherCompany() {
        login(10,Set.of("nxr:agent:workbench"));
        assertThat(accessController.context(null).company().id()).isEqualTo(101);
        assertThatThrownBy(()->accessController.save(30,new AgentOperatorScopeService.BindingRequest(101L,true))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->commerceController.photo(202L,9)).hasMessageContaining("403");
        assertThatThrownBy(()->commerceController.uploadPhoto(202L,null)).hasMessageContaining("403");
        assertThatThrownBy(()->commerceController.removeUnusedPhoto(202L,9)).hasMessageContaining("403");
        assertThatThrownBy(()->commerceController.order(202L,"OTHER")).hasMessageContaining("403");
        assertThatThrownBy(()->commerceController.updateAddress(202L,20,null)).hasMessageContaining("403");
        assertThatThrownBy(()->workbenchController.intake(202L,99)).hasMessageContaining("403");
        verifyNoInteractions(photos,portal,fulfillment,workbench);
    }
    @Test void enterpriseEndpointsDelegateOnlyResolvedMerchantAndNeverExposeRechargeApproval() {
        login(10,Set.of("nxr:agent:workbench"));
        var payment=new CustomerPortalService.WalletPaymentRequest("wallet-key");
        commerceController.pay(null,"NXR-OWN",payment);
        verify(portal).payOrderFromWallet(101,"NXR-OWN",payment);
        commerceController.batch(null,"MB-OWN");verify(batches).requireMerchantBatch(101,"MB-OWN");
        commerceController.addresses(null);verify(fulfillment).listAddresses(101);
        var recharge=new MerchantWalletService.RechargeRequest("USD",BigDecimal.TEN,"manual_transfer","payer","proof");
        commerceController.recharge(null,recharge);verify(wallet).createRecharge(101,recharge);verifyNoMoreInteractions(wallet);
        assertThat(Arrays.stream(AgentCommerceController.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
            .noneMatch(name->name.toLowerCase().contains("approve")||name.toLowerCase().contains("reviewrecharge"));
    }
    @Test void selectedCompanyQuoteMustMatchRequestCustomerAndPhotosResolveThroughBackendScope() {
        login(10,Set.of("nxr:agent:workbench"));
        assertThatThrownBy(()->commerceController.quote(null,202,"US","USD",1,null)).hasMessageContaining("403");
        assertThatThrownBy(()->commerceController.batchQuote(null,new CustomerCommercePolicyController.BatchQuoteRequest(202,"US","USD",null,List.of()))).hasMessageContaining("403");
        verifyNoInteractions(commerce);
        commerceController.photo(null,9);verify(photos).readOwned(101,9,false);
        commerceController.original(null,9);verify(photos).readOwned(101,9,true);
    }
    @Test void disablingBindingOrCompanyImmediatelyRejectsCachedBackendPermission() {
        login(10,Set.of("nxr:agent:workbench"));
        jdbc.update("UPDATE agent_operator_binding SET active=0 WHERE sys_user_id=10");
        assertThatThrownBy(()->commerceController.wallets(null)).hasMessageContaining("disabled");
        jdbc.update("UPDATE agent_operator_binding SET active=1 WHERE sys_user_id=10");
        jdbc.update("UPDATE customer_account SET is_active=0 WHERE id=101");
        assertThatThrownBy(()->commerceController.wallets(null)).hasMessageContaining("active merchant");
        verifyNoInteractions(wallet);
    }
    @Test void customerTokenRouteIsRemovedAndAllAgentControllersUseBackendMethodSecurity() {
        for(Class<?> type:List.of(AgentWorkbenchController.class,AgentCommerceController.class,AgentAdminAccessController.class)) {
            assertThat(type.getAnnotation(Anonymous.class)).isNull();
            assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/agent");
            assertThat(type.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)).isNotNull();
            for(var method:type.getDeclaredMethods()) for(var parameter:method.getParameters()) {
                var header=parameter.getAnnotation(org.springframework.web.bind.annotation.RequestHeader.class);
                if(header!=null) { assertThat(header.name()).isNotEqualTo("X-NXR-Customer-Token");assertThat(header.value()).isNotEqualTo("X-NXR-Customer-Token"); }
            }
        }
    }
    private void login(long id,Set<String> permissions) {
        SysUser user=new SysUser();user.setUserId(id);user.setUserName("user"+id);
        LoginUser login=new LoginUser(id,1L,user,permissions);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,"unused",List.of()));
    }
}
