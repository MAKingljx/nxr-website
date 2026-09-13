package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class PartnerManagementControllerTest {
    private AnnotationConfigApplicationContext context;
    private PartnerManagementService service;
    private PartnerManagementController controller;
    @Configuration @EnableMethodSecurity static class Security {
        @Bean(name="ss") Permissions permissions(){return new Permissions();}
    }
    public static class Permissions {
        public boolean hasPermi(String permission){try{var set=SecurityUtils.getLoginUser().getPermissions();return set.contains("*:*:*")||set.contains(permission);}catch(Exception e){return false;}}
    }
    @BeforeEach void setup(){
        service=mock(PartnerManagementService.class);context=new AnnotationConfigApplicationContext();context.register(Security.class);
        context.registerBean(PartnerManagementController.class,()->new PartnerManagementController(service));context.refresh();controller=context.getBean(PartnerManagementController.class);
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();context.close();}
    @Test void ordinaryPartnerCannotReadOrChangePlatformPartnersEvenWithWorkbenchPermission(){
        login(Set.of("nxr:agent:workbench"));
        assertThatThrownBy(()->controller.list(1,20,null,null,"USD")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.detail(101,"USD")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.provision(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.recharge(101,null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }
    @Test void openingNeedsUserAndCustomerPermissionsWhileRechargeNeedsFinancePermission(){
        login(Set.of("nxr:partner:list","nxr:partner:manage","nxr:customer:manage"));
        assertThatThrownBy(()->controller.provision(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.recharge(101,null)).isInstanceOf(AccessDeniedException.class);
        controller.update(101,null);verify(service).update(1,101,null);
        login(Set.of("nxr:partner:manage","system:user:add","nxr:customer:manage"));controller.provision(null);verify(service).provision(1,null);
        login(Set.of("nxr:partner:manage","nxr:customer:finance"));controller.recharge(101,null);verify(service).registerRecharge(1,101,null);
    }
    @Test void provisionAuditDoesNotCapturePasswordsOrResponsePayload() throws Exception {
        Log log=PartnerManagementController.class.getMethod("provision",PartnerManagementService.ProvisionRequest.class).getAnnotation(Log.class);
        assertThat(log.isSaveRequestData()).isFalse();assertThat(log.isSaveResponseData()).isFalse();
        assertThat(java.util.Arrays.stream(PartnerManagementController.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("approve","confirm","reviewRecharge");
    }
    private void login(Set<String> permissions){SysUser user=new SysUser();user.setUserId(1L);user.setUserName("admin");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L,1L,user,permissions),"unused",List.of()));}
}
