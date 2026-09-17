package com.nxr.platform.customer;

import com.nxr.platform.commerce.OrderAccessScopeService;
import com.ruoyi.common.utils.SecurityUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolves a RuoYi operator to one live merchant binding. Customer-session headers are never consulted. */
@Service
public class AgentOperatorScopeService {
    private final JdbcClient jdbc;
    private final OrderAccessScopeService access;
    public AgentOperatorScopeService(JdbcClient jdbc,OrderAccessScopeService access) { this.jdbc=jdbc;this.access=access; }
    public record Company(long id,String displayName,String email,String companyName) { }
    public record Context(boolean platformManager,Company company) { }
    public record Operator(long sysUserId,String userName,String nickName,long merchantCustomerId,String companyName,
        boolean active,LocalDateTime updatedAt) { }
    public record Candidate(long sysUserId,String userName,String nickName) { }
    public record BindingRequest(Long merchantCustomerId,Boolean active) { }
    private record Binding(long sysUserId,long merchantCustomerId,boolean active) { }
    private static final String COMPANY_SELECT="SELECT c.id,c.display_name,c.email,p.company_name FROM customer_account c LEFT JOIN merchant_company_profile p ON p.customer_id=c.id";
    private static final String OPERATOR_SELECT="""
        SELECT b.sys_user_id,u.user_name,u.nick_name,b.merchant_customer_id,p.company_name,b.active,b.updated_at
        FROM agent_operator_binding b JOIN sys_user u ON u.user_id=b.sys_user_id
        LEFT JOIN merchant_company_profile p ON p.customer_id=b.merchant_customer_id
        """;

    public long currentMerchant(Long requestedCompany) { return requireMerchant(SecurityUtils.getUserId(),requestedCompany); }
    public Context currentContext(Long requestedCompany) { return context(SecurityUtils.getUserId(),requestedCompany); }

    public long requireMerchant(long userId,Long requestedCompany) {
        Context context=context(userId,requestedCompany);
        if(context.company()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an agent company");
        return context.company().id();
    }

    public Context context(long userId,Long requestedCompany) {
        requireActiveUser(userId,false);
        Binding binding=binding(userId);
        if(binding!=null) {
            if(!binding.active()) throw forbidden("This agent operator binding is disabled");
            if(!hasAgentRole(userId) || hasOtherPrivileges(userId)) throw forbidden("This account is not an isolated agent operator");
            if(requestedCompany!=null && requestedCompany!=binding.merchantCustomerId()) throw forbidden("Agent company access is not allowed");
            return new Context(false,company(binding.merchantCustomerId()));
        }
        requireManager(userId);
        return new Context(true,requestedCompany==null?null:company(requestedCompany));
    }

    public AgentWorkbenchService.Page<Company> companies(long userId,String query,int page,int pageSize) {
        Context context=context(userId,null);
        int safePage=Math.max(1,page),safeSize=size(pageSize);
        if(!context.platformManager()) {
            Company c=context.company(); boolean matches=query==null||query.isBlank()||((c.displayName()+" "+c.email()+" "+c.companyName()).toLowerCase(java.util.Locale.ROOT).contains(query.strip().toLowerCase(java.util.Locale.ROOT)));
            return new AgentWorkbenchService.Page<>(matches&&safePage==1?List.of(c):List.of(),matches?1:0,safePage,safeSize);
        }
        Map<String,Object> p=new LinkedHashMap<>(); String where=" WHERE c.account_type_code='merchant' AND c.is_active=1";
        if(query!=null&&!query.isBlank()) {where+=" AND (LOWER(c.display_name) LIKE :query OR LOWER(c.email) LIKE :query OR LOWER(p.company_name) LIKE :query)";p.put("query",query(query));}
        long total=jdbc.sql("SELECT COUNT(*) FROM customer_account c LEFT JOIN merchant_company_profile p ON p.customer_id=c.id"+where).params(p).query(Long.class).single();
        p.put("limit",safeSize);p.put("offset",((long)safePage-1)*safeSize);
        return new AgentWorkbenchService.Page<>(jdbc.sql(COMPANY_SELECT+where+" ORDER BY c.id DESC LIMIT :limit OFFSET :offset").params(p).query(Company.class).list(),total,safePage,safeSize);
    }

    public AgentWorkbenchService.Page<Operator> operators(long actor,String query,int page,int pageSize) {
        requireManager(actor);
        Map<String,Object> p=new LinkedHashMap<>();String where=" WHERE 1=1";
        if(query!=null&&!query.isBlank()) {where+=" AND (LOWER(u.user_name) LIKE :query OR LOWER(u.nick_name) LIKE :query OR LOWER(p.company_name) LIKE :query)";p.put("query",query(query));}
        int safePage=Math.max(1,page),safeSize=size(pageSize);
        long total=jdbc.sql("SELECT COUNT(*) FROM agent_operator_binding b JOIN sys_user u ON u.user_id=b.sys_user_id LEFT JOIN merchant_company_profile p ON p.customer_id=b.merchant_customer_id"+where)
            .params(p).query(Long.class).single();
        p.put("limit",safeSize);p.put("offset",((long)safePage-1)*safeSize);
        return new AgentWorkbenchService.Page<>(jdbc.sql(OPERATOR_SELECT+where+" ORDER BY b.sys_user_id DESC LIMIT :limit OFFSET :offset").params(p).query(Operator.class).list(),total,safePage,safeSize);
    }

    public AgentWorkbenchService.Page<Candidate> candidates(long actor,String query,int page,int pageSize) {
        requireManager(actor);
        Map<String,Object> p=new LinkedHashMap<>();
        String where=" WHERE u.user_id<>1 AND u.status='0' AND u.del_flag='0' AND NOT EXISTS ("+otherPrivilegesSql("u.user_id")+")";
        if(query!=null&&!query.isBlank()) {where+=" AND (LOWER(u.user_name) LIKE :query OR LOWER(u.nick_name) LIKE :query)";p.put("query",query(query));}
        int safePage=Math.max(1,page),safeSize=size(pageSize);
        long total=jdbc.sql("SELECT COUNT(*) FROM sys_user u"+where).params(p).query(Long.class).single();
        p.put("limit",safeSize);p.put("offset",((long)safePage-1)*safeSize);
        return new AgentWorkbenchService.Page<>(jdbc.sql("SELECT u.user_id AS sys_user_id,u.user_name,u.nick_name FROM sys_user u"+where+" ORDER BY u.user_id DESC LIMIT :limit OFFSET :offset")
            .params(p).query(Candidate.class).list(),total,safePage,safeSize);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Operator saveBinding(long actor,long userId,BindingRequest request) {
        requireManager(actor);
        return saveBindingInternal(actor,userId,request);
    }

    /** Only the platform provisioning flow may bind a newly inserted, unprivileged operator without the legacy binding permission. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Operator bindNewPartnerOperator(long actor,long userId,long merchant) {
        requirePlatformPermissions(actor,"nxr:partner:manage","system:user:add","nxr:customer:manage");
        if(jdbc.sql("SELECT COUNT(*) FROM sys_user_role WHERE user_id=:id").param("id",userId).query(Long.class).single()!=0)
            throw forbidden("A newly provisioned operator must not have existing roles");
        return saveBindingInternal(actor,userId,new BindingRequest(merchant,true));
    }

    private Operator saveBindingInternal(long actor,long userId,BindingRequest request) {
        if(request==null||request.active()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Company and active state are required");
        jdbc.sql("SELECT user_id FROM sys_user WHERE user_id=:id"+(request.active()?" AND status='0' AND del_flag='0'":"")+" FOR UPDATE")
            .param("id",userId).query(Long.class).optional().orElseThrow(()->forbidden("An available backend account is required"));
        Binding previous=binding(userId);
        if(request.active()) {
            if(userId==actor||userId==1||hasOtherPrivileges(userId)) throw forbidden("Use a backend account without platform or other business permissions");
            if(request.merchantCustomerId()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an agent company");
            company(request.merchantCustomerId());
        } else {
            if(previous==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent binding not found");
            if(request.merchantCustomerId()!=null && request.merchantCustomerId()!=previous.merchantCustomerId()) throw forbidden("Disabling a binding cannot change its company");
        }
        long merchant=request.active()?request.merchantCustomerId():previous.merchantCustomerId();
        Long role=request.active()?agentRole():null;
        if(previous==null) {
            jdbc.sql("INSERT INTO agent_operator_binding(sys_user_id,merchant_customer_id,active,created_by_user_id,updated_by_user_id) VALUES(:user,:merchant,:active,:actor,:actor)")
                .params(params("user",userId,"merchant",merchant,"active",1,"actor",actor)).update();
        } else {
            jdbc.sql("UPDATE agent_operator_binding SET merchant_customer_id=:merchant,active=:active,updated_by_user_id=:actor,updated_at=CURRENT_TIMESTAMP WHERE sys_user_id=:user")
                .params(params("merchant",merchant,"active",request.active()?1:0,"actor",actor,"user",userId)).update();
        }
        if(request.active()) {
            jdbc.sql("INSERT INTO sys_user_role(user_id,role_id) SELECT :user,:role WHERE NOT EXISTS(SELECT 1 FROM sys_user_role WHERE user_id=:user AND role_id=:role)")
                .params(params("user",userId,"role",role)).update();
        } else {
            jdbc.sql("DELETE FROM sys_user_role WHERE user_id=:user AND role_id IN(SELECT role_id FROM sys_role WHERE role_key='nxr_agent')").param("user",userId).update();
        }
        if(previous==null||previous.active()!=request.active()||previous.merchantCustomerId()!=merchant) {
            jdbc.sql("INSERT INTO agent_operator_event(sys_user_id,merchant_customer_id,active,changed_by_user_id) VALUES(:user,:merchant,:active,:actor)")
                .params(params("user",userId,"merchant",merchant,"active",request.active()?1:0,"actor",actor)).update();
        }
        return jdbc.sql(OPERATOR_SELECT+" WHERE b.sys_user_id=:user").param("user",userId).query(Operator.class).single();
    }

    public void requireManager(long userId) {
        requireActiveUser(userId,false);
        if(binding(userId)!=null || !livePermission(userId,"nxr:agent:manage")) throw forbidden("Agent operator management is not allowed");
        access.requireUnrestricted(userId,"Agent operator management");
    }
    public void requirePlatformPermissions(long userId,String... permissions) {
        requireActiveUser(userId,false);
        if(binding(userId)!=null) throw forbidden("Partner operators cannot access platform management");
        access.requireUnrestricted(userId,"Partner management");
        for(String permission:permissions) if(!livePermission(userId,permission)) throw forbidden("Required platform permission: "+permission);
    }
    public boolean hasPlatformPermission(long userId,String permission) { return livePermission(userId,permission); }

    private void requireActiveUser(long userId,boolean lock) {
        long found=jdbc.sql("SELECT user_id FROM sys_user WHERE user_id=:id AND status='0' AND del_flag='0'"+(lock?" FOR UPDATE":""))
            .param("id",userId).query(Long.class).optional().orElseThrow(()->forbidden("An active backend account is required"));
        if(found<=0) throw forbidden("Backend authentication is required");
    }
    private Binding binding(long id) {
        return jdbc.sql("SELECT sys_user_id,merchant_customer_id,active FROM agent_operator_binding WHERE sys_user_id=:id")
            .param("id",id).query(Binding.class).optional().orElse(null);
    }
    private Company company(long id) {
        return jdbc.sql(COMPANY_SELECT+" WHERE c.id=:id AND c.account_type_code='merchant' AND c.is_active=1").param("id",id)
            .query(Company.class).optional().orElseThrow(()->forbidden("An active merchant company is required"));
    }
    private boolean hasAgentRole(long userId) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id
            JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
            WHERE ur.user_id=:user AND r.role_key='nxr_agent' AND r.status='0' AND r.del_flag='0'
              AND m.status='0' AND m.perms='nxr:agent:workbench'
            """).param("user",userId).query(Long.class).single()>0;
    }
    private long agentRole() {
        List<Long> roles=jdbc.sql("SELECT role_id FROM sys_role WHERE role_key='nxr_agent' AND status='0' AND del_flag='0' FOR UPDATE").query(Long.class).list();
        if(roles.size()!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"The isolated agent role is missing, disabled or ambiguous");
        long role=roles.get(0);
        if(jdbc.sql("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=:role AND m.perms='nxr:agent:workbench' AND m.status='0'")
            .param("role",role).query(Long.class).single()!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"The agent workspace permission is unavailable");
        if(jdbc.sql("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=:role AND COALESCE(m.perms,'')<>'' AND m.perms<>'nxr:agent:workbench'")
            .param("role",role).query(Long.class).single()!=0) throw forbidden("The agent role contains platform permissions");
        return role;
    }
    private boolean livePermission(long userId,String permission) {
        if(userId==1) return true;
        return jdbc.sql("""
            SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id
            JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
            WHERE ur.user_id=:user AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms IN(:permission,'*:*:*')
            """).param("user",userId).param("permission",permission).query(Long.class).single()>0;
    }
    private boolean hasOtherPrivileges(long userId) {
        return userId==1 || jdbc.sql("SELECT COUNT(*) FROM ("+otherPrivilegesSql(":user")+") forbidden_roles").param("user",userId).query(Long.class).single()>0;
    }
    // Dormant privileged roles are also excluded: reactivation or a previously cached token must not widen an agent account.
    private static String otherPrivilegesSql(String user) {
        return "SELECT r.role_id FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id="+user+
            " AND (r.role_key='admin' OR EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=r.role_id AND COALESCE(m.perms,'')<>'' AND m.perms<>'nxr:agent:workbench'))";
    }
    private static Map<String,Object> params(Object... pairs) { Map<String,Object> p=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)p.put((String)pairs[i],pairs[i+1]);return p; }
    private static int size(int value) { return Math.min(100,Math.max(1,value)); }
    private static String query(String value) { if(value.length()>255)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Search is too long");return "%"+value.strip().toLowerCase(java.util.Locale.ROOT)+"%"; }
    private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN,message); }
}
