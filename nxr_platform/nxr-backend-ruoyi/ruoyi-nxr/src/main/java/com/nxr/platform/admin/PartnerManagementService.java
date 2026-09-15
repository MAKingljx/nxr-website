package com.nxr.platform.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.customer.AgentOperatorScopeService;
import com.nxr.platform.customer.AgentWorkbenchService;
import com.nxr.platform.customer.MerchantBatchService;
import com.nxr.platform.customer.MerchantWalletService;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Platform management of merchant partners; funds remain in the established prepaid wallet ledger. */
@Service
public class PartnerManagementService {
    private final JdbcClient jdbc;
    private final AgentOperatorScopeService scope;
    private final MerchantWalletService wallet;
    private final MerchantBatchService batches;
    private final ObjectMapper json;
    private final Validator validator;
    private final SimpleJdbcInsert customerInsert;
    private final SimpleJdbcInsert userInsert;
    public PartnerManagementService(JdbcClient jdbc,JdbcTemplate template,AgentOperatorScopeService scope,
        MerchantWalletService wallet,MerchantBatchService batches,ObjectMapper json,Validator validator) {
        this.jdbc=jdbc;this.scope=scope;this.wallet=wallet;this.batches=batches;this.json=json;this.validator=validator;
        customerInsert=new SimpleJdbcInsert(template).withTableName("customer_account").usingGeneratedKeyColumns("id")
            .usingColumns("email","password_hash","display_name","mobile","account_type_code","is_active");
        userInsert=new SimpleJdbcInsert(template).withTableName("sys_user").usingGeneratedKeyColumns("user_id")
            .usingColumns("user_name","nick_name","password","user_type","status","del_flag","create_by","create_time","pwd_update_date");
    }
    public record PartnerSummary(long id,String displayName,String email,String mobile,String companyName,String contactName,
        boolean active,long operatorCount,long activeOperatorCount,String operatorAccounts,long clientCount,long inventoryCount,
        long pendingBatchCount,String currencyCode,@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal walletBalance,Long pendingRechargeCount,boolean financeVisible,LocalDateTime createdAt) { }
    public record Operator(long sysUserId,String userName,String nickName,boolean active,boolean backendActive) { }
    public record Detail(PartnerSummary partner,List<Operator> operators,List<MerchantWalletService.WalletBalance> wallets,
        MerchantWalletService.RechargePage recharges,MerchantBatchService.BatchPage batches,boolean financeVisible) { }
    public record ProvisionRequest(String requestKey,Long existingCustomerId,String email,String displayName,String mobile,
        String companyName,String contactName,String userName,String nickName,
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password) {
        @Override public String toString() { return "ProvisionRequest[requestKey="+requestKey+", existingCustomerId="+existingCustomerId+", credentials omitted]"; }
    }
    public record ProvisionResult(long customerId,long sysUserId,boolean replayed) { }
    public record UpdateRequest(String displayName,String email,String mobile,String companyName,String contactName,Boolean active) { }
    public record RechargeRequest(String requestKey,String currencyCode,BigDecimal amount,String providerCode,String payerReference,String proofReference,Long settingsVersion) {
        public RechargeRequest(String requestKey,String currencyCode,BigDecimal amount,String providerCode,String payerReference,String proofReference) {
            this(requestKey,currencyCode,amount,providerCode,payerReference,proofReference,null);
        }
    }
    private record Command(long customerId,Long sysUserId,Long rechargeId,String requestHash) { }

    private static final String SUMMARY_COLUMNS="""
        SELECT c.id,c.display_name,c.email,c.mobile,p.company_name,p.contact_name,c.is_active,c.created_at,
          (SELECT COUNT(*) FROM agent_operator_binding b WHERE b.merchant_customer_id=c.id) AS operator_count,
          (SELECT COUNT(*) FROM agent_operator_binding b JOIN sys_user u ON u.user_id=b.sys_user_id
            WHERE b.merchant_customer_id=c.id AND b.active=1 AND u.status='0' AND u.del_flag='0' AND c.is_active=1) AS active_operator_count,
          (SELECT GROUP_CONCAT(u.user_name ORDER BY u.user_id SEPARATOR ', ') FROM agent_operator_binding b JOIN sys_user u ON u.user_id=b.sys_user_id
            WHERE b.merchant_customer_id=c.id) AS operator_accounts,
          (SELECT COUNT(*) FROM agent_client a WHERE a.merchant_customer_id=c.id) AS client_count,
          (SELECT COUNT(*) FROM agent_card a WHERE a.merchant_customer_id=c.id AND a.status_code IN('in_stock','exception','returned')) AS inventory_count,
          (SELECT COUNT(*) FROM merchant_order_batch b WHERE b.merchant_customer_id=c.id AND b.status_code NOT IN('delivered','cancelled')) AS pending_batch_count
        """;
    private static final String SUMMARY_FROM=" FROM customer_account c LEFT JOIN merchant_company_profile p ON p.customer_id=c.id";

    public AgentWorkbenchService.Page<PartnerSummary> list(long actor,int page,int pageSize,String query,Boolean active,String currencyCode) {
        scope.requirePlatformPermissions(actor,"nxr:partner:list");
        boolean finance=scope.hasPlatformPermission(actor,"nxr:customer:finance");String currency=wallet.usesEnterpriseCredit()?"PTS":currency(currencyCode);
        Map<String,Object> params=params("currency",currency);String where=" WHERE c.account_type_code='merchant'";
        if(active!=null) {where+=" AND c.is_active=:active";params.put("active",active?1:0);}
        if(optional(query,255)!=null) {where+=" AND (LOWER(c.email) LIKE :query OR LOWER(c.display_name) LIKE :query OR LOWER(p.company_name) LIKE :query OR LOWER(p.contact_name) LIKE :query OR EXISTS(SELECT 1 FROM agent_operator_binding b JOIN sys_user u ON u.user_id=b.sys_user_id WHERE b.merchant_customer_id=c.id AND LOWER(u.user_name) LIKE :query))";params.put("query","%"+query.strip().toLowerCase(Locale.ROOT)+"%");}
        long total=jdbc.sql("SELECT COUNT(*)"+SUMMARY_FROM+where).params(params).query(Long.class).single();
        int safePage=Math.max(1,page),safeSize=Math.min(100,Math.max(1,pageSize));params.put("limit",safeSize);params.put("offset",((long)safePage-1)*safeSize);
        List<PartnerSummary> items=jdbc.sql(summarySelect(finance)+where+" ORDER BY c.id DESC LIMIT :limit OFFSET :offset")
            .params(params).query((rs,n)->summary(rs,finance,currency)).list();
        return new AgentWorkbenchService.Page<>(items,total,safePage,safeSize);
    }

    public Detail detail(long actor,long customerId,String currencyCode) {
        scope.requirePlatformPermissions(actor,"nxr:partner:list");boolean finance=scope.hasPlatformPermission(actor,"nxr:customer:finance");
        PartnerSummary summary=requirePartner(customerId,finance,wallet.usesEnterpriseCredit()?"PTS":currency(currencyCode));
        List<Operator> operators=jdbc.sql("""
            SELECT b.sys_user_id,u.user_name,u.nick_name,b.active,
              CASE WHEN u.status='0' AND u.del_flag='0' THEN TRUE ELSE FALSE END AS backend_active
            FROM agent_operator_binding b JOIN sys_user u ON u.user_id=b.sys_user_id
            WHERE b.merchant_customer_id=:id ORDER BY b.sys_user_id
            """).param("id",customerId).query(Operator.class).list();
        return new Detail(summary,operators,finance?wallet.listWallets(customerId):List.of(),
            finance?wallet.listRecharges(customerId,null,1,20):new MerchantWalletService.RechargePage(List.of(),1,20,0),
            batches.listMerchantBatches(customerId,1,20),finance);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ProvisionResult provision(long actor,ProvisionRequest request) {
        scope.requirePlatformPermissions(actor,"nxr:partner:manage","system:user:add","nxr:customer:manage");
        if(request==null) throw bad("Partner details are required");
        String key=required(request.requestKey(),"Request key",128);
        String password=request.password()==null?"":request.password();
        if(password.length()<UserConstants.PASSWORD_MIN_LENGTH||password.length()>UserConstants.PASSWORD_MAX_LENGTH)
            throw bad("Backend password must contain 5 to 20 characters");
        String userName=required(request.userName(),"Backend username",UserConstants.USERNAME_MAX_LENGTH);
        if(userName.length()<UserConstants.USERNAME_MIN_LENGTH) throw bad("Backend username must contain 2 to 20 characters");
        String nick=optional(request.nickName(),30);if(nick==null)nick=userName;
        SysUser candidate=new SysUser();candidate.setUserName(userName);candidate.setNickName(nick);
        var validation=validator.validate(candidate);if(!validation.isEmpty())throw bad(validation.iterator().next().getMessage());
        // Serialize this provisioning path across administrators because RuoYi's legacy username column is not unique.
        List<Long> roles=jdbc.sql("SELECT role_id FROM sys_role WHERE role_key='nxr_agent' AND status='0' AND del_flag='0' FOR UPDATE").query(Long.class).list();
        if(roles.size()!=1)throw conflict("The partner operator role is missing, disabled or ambiguous");
        String fingerprint=hash(request); // WRITE_ONLY password is deliberately excluded from all retained command data.
        Command old=command(actor,"provision",key,fingerprint);
        if(old!=null) {
            String currentHash=jdbc.sql("SELECT password FROM sys_user WHERE user_id=:id").param("id",old.sysUserId()).query(String.class).single();
            if(!SecurityUtils.matchesPassword(password,currentHash))throw conflict("This request key already provisioned an account with different credentials");
            return new ProvisionResult(old.customerId(),old.sysUserId(),true);
        }
        if(jdbc.sql("SELECT COUNT(*) FROM sys_user WHERE LOWER(user_name)=:name").param("name",userName.toLowerCase(Locale.ROOT)).query(Long.class).single()>0)
            throw conflict("The backend username is already in use");
        long customer;
        if(request.existingCustomerId()!=null) {
            customer=request.existingCustomerId();lockCustomer(customer);
            PartnerSummary existing=requirePartner(customer,false,"USD");
            if(!existing.active())throw conflict("Enable this partner before opening a backend account");
            if(optional(request.email(),191)!=null&&!email(request.email()).equalsIgnoreCase(existing.email()))throw conflict("The selected company does not match the provided email");
            if(optional(request.companyName(),191)!=null&&!request.companyName().strip().equals(existing.companyName()))throw conflict("Existing company details must be changed through its profile");
        } else {
            String email=email(request.email());String name=required(request.displayName(),"Display name",128);
            String company=required(request.companyName(),"Company name",191),contact=required(request.contactName(),"Contact name",128);
            if(jdbc.sql("SELECT COUNT(*) FROM customer_account WHERE LOWER(email)=:email").param("email",email).query(Long.class).single()>0)
                throw conflict("The email is already registered. Select the existing merchant explicitly");
            byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
            // Backend credentials are never shared with the separate customer identity or returned as a customer session.
            Map<String,Object> row=params("email",email,"password_hash",SecurityUtils.encryptPassword(Base64.getEncoder().encodeToString(secret)),
                "display_name",name,"mobile",optional(request.mobile(),64),"account_type_code","merchant","is_active",1);
            try {customer=customerInsert.executeAndReturnKey(row).longValue();}
            catch(DataIntegrityViolationException duplicate) {throw conflict("The email is already registered. Select the existing merchant explicitly");}
            wallet.saveMerchantProfile(customer,new MerchantWalletService.MerchantProfileRequest(company,contact));
        }
        String actorName=jdbc.sql("SELECT user_name FROM sys_user WHERE user_id=:id").param("id",actor).query(String.class).single();
        long sysUser=userInsert.executeAndReturnKey(params("user_name",userName,"nick_name",nick,"password",SecurityUtils.encryptPassword(password),
            "user_type","00","status","0","del_flag","0","create_by",actorName,"create_time",LocalDateTime.now(),"pwd_update_date",null)).longValue();
        scope.bindNewPartnerOperator(actor,sysUser,customer);
        recordCommand(actor,"provision",key,fingerprint,customer,sysUser,null);
        return new ProvisionResult(customer,sysUser,false);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public PartnerSummary update(long actor,long customerId,UpdateRequest request) {
        scope.requirePlatformPermissions(actor,"nxr:partner:manage","nxr:customer:manage");
        if(request==null||request.active()==null)throw bad("Partner profile and active state are required");
        lockCustomer(customerId);requirePartner(customerId,false,"USD");
        String email=email(request.email());
        if(jdbc.sql("SELECT COUNT(*) FROM customer_account WHERE LOWER(email)=:email AND id<>:id").params(params("email",email,"id",customerId)).query(Long.class).single()>0)
            throw conflict("The email is already registered");
        jdbc.sql("UPDATE customer_account SET display_name=:name,email=:email,mobile=:mobile,is_active=:active,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND account_type_code='merchant'")
            .params(params("name",required(request.displayName(),"Display name",128),"email",email,"mobile",optional(request.mobile(),64),"active",request.active()?1:0,"id",customerId)).update();
        wallet.saveMerchantProfile(customerId,new MerchantWalletService.MerchantProfileRequest(required(request.companyName(),"Company name",191),required(request.contactName(),"Contact name",128)));
        // Matches customer-account deactivation: existing customer sessions expire; backend bindings fail live company checks.
        if(!request.active())jdbc.sql("DELETE FROM customer_session WHERE customer_id=:id").param("id",customerId).update();
        return requirePartner(customerId,scope.hasPlatformPermission(actor,"nxr:customer:finance"),"USD");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public MerchantWalletService.RechargeRecord registerRecharge(long actor,long customerId,RechargeRequest request) {
        scope.requirePlatformPermissions(actor,"nxr:partner:manage","nxr:customer:finance");
        if(request==null)throw bad("Offline recharge details are required");
        lockCustomer(customerId);PartnerSummary partner=requirePartner(customerId,false,"USD");
        String key=required(request.requestKey(),"Request key",128),fingerprint=hash(List.of(customerId,request));
        Command old=command(actor,"recharge",key,fingerprint);
        if(old!=null)return recharge(customerId,old.rechargeId());
        if(!partner.active())throw conflict("Enable this partner before registering a new recharge");
        String provider=required(request.providerCode(),"Transfer method",32).toLowerCase(Locale.ROOT);
        if(!Set.of("manual_transfer","bank_transfer","wechat_transfer","alipay_transfer").contains(provider))throw bad("Only an offline transfer application can be registered here");
        var recharge=wallet.createRecharge(customerId,new MerchantWalletService.RechargeRequest(request.currencyCode(),request.amount(),provider,request.payerReference(),request.proofReference(),request.settingsVersion()));
        recordCommand(actor,"recharge",key,fingerprint,customerId,null,recharge.id());
        return recharge;
    }

    private MerchantWalletService.RechargeRecord recharge(long customer,long id) {
        return wallet.requireRecharge(customer,id);
    }
    private void lockCustomer(long id) {
        jdbc.sql("SELECT id FROM customer_account WHERE id=:id AND account_type_code='merchant' FOR UPDATE").param("id",id).query(Long.class).optional()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Partner not found"));
    }
    private Command command(long actor,String operation,String key,String fingerprint) {
        Command found=jdbc.sql("SELECT customer_id,sys_user_id,recharge_id,request_hash FROM partner_management_command WHERE actor_user_id=:actor AND operation_code=:operation AND request_key=:key")
            .params(params("actor",actor,"operation",operation,"key",key)).query(Command.class).optional().orElse(null);
        if(found!=null&&!fingerprint.equals(found.requestHash()))throw conflict("This request key was already used for different details");
        return found;
    }
    private void recordCommand(long actor,String operation,String key,String fingerprint,long customer,Long user,Long recharge) {
        jdbc.sql("INSERT INTO partner_management_command(actor_user_id,operation_code,request_key,request_hash,customer_id,sys_user_id,recharge_id) VALUES(:actor,:operation,:key,:hash,:customer,:user,:recharge)")
            .params(params("actor",actor,"operation",operation,"key",key,"hash",fingerprint,"customer",customer,"user",user,"recharge",recharge)).update();
    }
    private PartnerSummary requirePartner(long id,boolean finance,String currency) {
        return jdbc.sql(summarySelect(finance)+" WHERE c.id=:id AND c.account_type_code='merchant'")
            .params(params("id",id,"currency",currency)).query((rs,n)->summary(rs,finance,currency)).optional()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Partner not found"));
    }
    private String summarySelect(boolean finance) {
        String money=finance?",COALESCE((SELECT w.balance FROM merchant_wallet w WHERE w.customer_id=c.id AND w.currency_code=:currency),0) AS wallet_balance,(SELECT COUNT(*) FROM merchant_wallet_recharge r WHERE r.customer_id=c.id AND r.status_code='pending') AS pending_recharge_count"
            :",NULL AS wallet_balance,NULL AS pending_recharge_count";
        return SUMMARY_COLUMNS+money+SUMMARY_FROM;
    }
    private PartnerSummary summary(java.sql.ResultSet rs,boolean finance,String currency) throws java.sql.SQLException {
        return new PartnerSummary(rs.getLong("id"),rs.getString("display_name"),rs.getString("email"),rs.getString("mobile"),rs.getString("company_name"),rs.getString("contact_name"),
            rs.getBoolean("is_active"),rs.getLong("operator_count"),rs.getLong("active_operator_count"),rs.getString("operator_accounts"),rs.getLong("client_count"),rs.getLong("inventory_count"),
            rs.getLong("pending_batch_count"),currency,rs.getBigDecimal("wallet_balance"),rs.getObject("pending_recharge_count",Long.class),finance,rs.getObject("created_at",LocalDateTime.class));
    }
    private String hash(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error) {throw new IllegalStateException("Cannot fingerprint partner command",error);}
    }
    private static String currency(String value) {
        String currency=optional(value,8);currency=currency==null?"USD":currency.toUpperCase(Locale.ROOT);
        if(!MerchantWalletService.SUPPORTED_CURRENCIES.contains(currency))throw bad("Unsupported currency");return currency;
    }
    private static String email(String value) {
        String email=required(value,"Email",191).toLowerCase(Locale.ROOT);
        if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw bad("A valid email address is required");return email;
    }
    private static Map<String,Object> params(Object... pairs) {Map<String,Object> p=new LinkedHashMap<>();for(int n=0;n<pairs.length;n+=2)p.put((String)pairs[n],pairs[n+1]);return p;}
    private static String optional(String value,int max) {if(value==null||value.isBlank())return null;String clean=value.strip();if(clean.length()>max)throw bad("Text exceeds "+max+" characters");return clean;}
    private static String required(String value,String label,int max) {String clean=optional(value,max);if(clean==null)throw bad(label+" is required");return clean;}
    private static ResponseStatusException bad(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
