package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.sql.Connection;
import java.util.ArrayList;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminCardOperatorServiceTest {
    private JdbcTemplate jdbc;
    private JdbcDataSource dataSource;
    private AdminCardOperatorService service;
    private TransactionTemplate transaction;
    private ValidatorFactory validators;
    private final List<Long> revoked = new ArrayList<>();
    private ObjectMapper json;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        dataSource = ds;
        ds.setURL("jdbc:h2:mem:card_operator_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(ds);
        try (Connection connection = ds.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("card_operator_h2.sql")); }
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        validators = Validation.buildDefaultValidatorFactory();
        CardOperatorSessionInvalidator invalidator = userId -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { revoked.add(userId); }
            });
        };
        service = new AdminCardOperatorService(JdbcClient.create(jdbc), jdbc, validators.getValidator(), invalidator);
        json = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    @AfterEach void close() { validators.close(); }

    @Test void onlyPureOperatorsAppearIncludingExistingAdminCreatedAccounts() {
        var page = service.list(50, 1, 20, null, null);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(AdminCardOperatorService.Operator::userId).containsExactly(102L, 101L);
        assertThat(page.items()).extracting(AdminCardOperatorService.Operator::status).containsExactly("1", "0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_account WHERE id=101", Long.class)).isEqualTo(1);
    }
    @Test void paginationStatusAndLiteralSearchAreBounded() {
        assertThat(service.list(50, 1, 1, "NXr_CARD", null).items()).extracting(AdminCardOperatorService.Operator::userId).containsExactly(102L);
        assertThat(service.list(50, 2, 1, "nxr_card", null).items()).extracting(AdminCardOperatorService.Operator::userId).containsExactly(101L);
        assertThat(service.list(50, 1, 20, "Uploader", "0").items()).extracting(AdminCardOperatorService.Operator::userId).containsExactly(101L);
        assertThat(service.list(50, 1, 20, "%", null).total()).isZero();
        assertThat(service.list(50, -1, 9999, null, null).pageSize()).isEqualTo(100);
        assertThat(service.list(50, Integer.MAX_VALUE, 20, null, null).page()).isEqualTo(10000);
        assertThatThrownBy(() -> service.list(50, 1, 20, "x".repeat(101), null)).hasMessageContaining("supported length");
        assertThatThrownBy(() -> service.list(50, 1, 20, null, "active")).hasMessageContaining("status");
    }
    @Test void newAccountReceivesOnlyFixedRoleAndHashWithoutTouchingBusinessBindings() throws Exception {
        var request = new AdminCardOperatorService.CreateRequest("new_upload_01", "New uploader", "UploadPass1");
        var created = create(50, request);
        assertThat(created.status()).isEqualTo("0");
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?", Long.class, created.userId())).containsExactly(107L);
        String stored = jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=?", String.class, created.userId());
        assertThat(stored).isNotEqualTo("UploadPass1");
        assertThat(SecurityUtils.matchesPassword("UploadPass1", stored)).isTrue();
        assertThat(jdbc.queryForObject("SELECT dept_id FROM sys_user WHERE user_id=?", Long.class, created.userId())).isNull();
        assertThat(jdbc.queryForObject("SELECT create_by FROM sys_user WHERE user_id=?", String.class, created.userId())).isEqualTo("nxr_card_super_01");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_operator_binding WHERE sys_user_id=?", Long.class, created.userId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_account", Long.class)).isEqualTo(1);
        assertThat(json.writeValueAsString(created)).doesNotContain("password", stored, "UploadPass1");
        assertThat(json.writeValueAsString(request)).doesNotContain("password", "UploadPass1");
        assertThat(request.toString()).doesNotContain("UploadPass1");
        assertThat(revoked).isEmpty();
    }
    @Test void statusAndPasswordChangesRevokeOnlyTargetAfterCommit() throws Exception {
        changeStatus(50, 101, "1");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE user_id=101", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE user_id=102", String.class)).isEqualTo("1");
        assertThat(revoked).containsExactly(101L);
        changeStatus(50, 101, "0");
        var request = new AdminCardOperatorService.PasswordRequest("NewUploadPass2");
        var result = transaction.execute(state -> service.resetPassword(50, 101, request));
        assertThat(result.passwordReset()).isTrue();
        String hash = jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=101", String.class);
        assertThat(SecurityUtils.matchesPassword("NewUploadPass2", hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=102", String.class)).isEqualTo("old");
        assertThat(jdbc.queryForObject("SELECT pwd_update_date FROM sys_user WHERE user_id=101", java.sql.Timestamp.class)).isNotNull();
        assertThat(revoked).containsExactly(101L, 101L, 101L);
        assertThat(json.writeValueAsString(result)).doesNotContain("NewUploadPass2", hash);
        assertThat(json.writeValueAsString(request)).doesNotContain("password", "NewUploadPass2");
        assertThat(request.toString()).doesNotContain("NewUploadPass2");
    }
    @Test void protectedTargetsRejectStatusAndResetWithNoCredentialOrRoleChanges() {
        long users = count("sys_user"), roles = count("sys_user_role");
        for (long target : new long[] { 1, 50, 51, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 99999 }) {
            assertThatThrownBy(() -> changeStatus(50, target, "1")).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> reset(50, target, "NewUploadPass2")).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(count("sys_user")).isEqualTo(users);
        assertThat(count("sys_user_role")).isEqualTo(roles);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE password<>'old'", Long.class)).isZero();
        assertThat(revoked).isEmpty();
    }
    @Test void dormantAndOrphanRoleAssociationsStillExcludeTargets() {
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=109");
        assertThatThrownBy(() -> reset(50, 203, "NewUploadPass2")).hasMessageContaining("roles outside");
        assertThatThrownBy(() -> reset(50, 209, "NewUploadPass2")).hasMessageContaining("roles outside");
        assertThat(service.list(50, 1, 20, null, null).total()).isEqualTo(2);
    }
    @Test void liveActorRoleStatusAndPermissionOverridesAnyCachedClaim() {
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=108 AND menu_id=2");
        assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest("new_upload_01", null, "UploadPass1"))).hasMessageContaining("action is not allowed");
        assertThat(service.list(50, 1, 20, null, null).total()).isEqualTo(2);
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=50");
        assertThatThrownBy(() -> service.list(50, 1, 20, null, null)).hasMessageContaining("active card");
        jdbc.update("UPDATE sys_user SET status='0' WHERE user_id=50");
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=108");
        assertThatThrownBy(() -> changeStatus(50, 101, "1")).hasMessageContaining("roles outside");
        assertThat(revoked).isEmpty();
    }
    @Test void cardOperatorsOrdinaryAdminsAndBoundManagersCannotUseThisEndpoint() {
        for (long actor : new long[] { 101, 201, 202, 203, 204, 205, 2080 }) {
            assertThatThrownBy(() -> service.list(actor, 1, 20, null, null)).isInstanceOf(ResponseStatusException.class);
        }
        jdbc.update("INSERT INTO agent_operator_binding VALUES(50,900,FALSE)");
        assertThatThrownBy(() -> service.list(50, 1, 20, null, null)).hasMessageContaining("dedicated card");
        assertThatThrownBy(() -> reset(50, 101, "NewUploadPass2")).hasMessageContaining("dedicated card");
    }
    @Test void cardRolePollutedWithAnyDormantFinancialOrPlatformPermissionFailsClosed() {
        jdbc.update("UPDATE sys_menu SET status='1' WHERE menu_id=9");
        jdbc.update("INSERT INTO sys_role_menu VALUES(107,9)");
        assertThatThrownBy(() -> service.list(50, 1, 20, null, null)).hasMessageContaining("outside this business");
        assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest("new_upload_01", null, "UploadPass1"))).hasMessageContaining("outside this business");
        assertThatThrownBy(() -> reset(50, 101, "NewUploadPass2")).hasMessageContaining("outside this business");
        assertThat(revoked).isEmpty();
    }
    @Test void managerCannotUseAnExtraRoleOrMixedPermissionSet() {
        jdbc.update("INSERT INTO sys_user_role VALUES(50,109)");
        assertThatThrownBy(() -> service.list(50, 1, 20, null, null)).hasMessageContaining("roles outside");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=50 AND role_id=109");
        jdbc.update("INSERT INTO sys_role_menu VALUES(108,9)");
        assertThatThrownBy(() -> service.list(50, 1, 20, null, null)).hasMessageContaining("outside this business");
    }
    @Test void activePlatformRootCanOverseeTheSameBoundedBusinessAccounts() {
        assertThat(service.list(1,1,20,null,null).items()).extracting(AdminCardOperatorService.Operator::userId).containsExactly(102L,101L);
        var created=create(1,new AdminCardOperatorService.CreateRequest("root_opened_card",null,"UploadPass1"));
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?",Long.class,created.userId())).containsExactly(107L);
        changeStatus(1,101,"1");
        reset(1,101,"NewUploadPass2");
        assertThat(revoked).containsExactly(101L,101L);
        for(long target:new long[]{1,50,51,201,202,203,208}) {
            assertThatThrownBy(()->reset(1,target,"NewUploadPass2")).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=1",Long.class)).containsExactly(1L);
    }
    @Test void platformRootMustStillBeLiveAndHaveItsActivePlatformRole() {
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=1");
        assertThatThrownBy(()->service.list(1,1,20,null,null)).hasMessageContaining("active card");
        jdbc.update("UPDATE sys_user SET status='0' WHERE user_id=1");
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=1");
        assertThatThrownBy(()->reset(1,101,"NewUploadPass2")).hasMessageContaining("active platform");
        assertThat(revoked).isEmpty();
    }
    @Test void missingDisabledOrAmbiguousOperatorRoleNeverFallsBackToAdmin() {
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=107");
        assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest("new_upload_01", null, "UploadPass1"))).hasMessageContaining("unavailable or ambiguous");
        jdbc.update("UPDATE sys_role SET status='0' WHERE role_id=107");
        jdbc.update("INSERT INTO sys_role VALUES(307,'nxr_card_manager','0','0')");
        assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest("new_upload_01", null, "UploadPass1"))).hasMessageContaining("unavailable or ambiguous");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_name='new_upload_01'", Long.class)).isZero();
    }
    @Test void existingDeletedCaseInsensitiveNamesAreNeverAdoptedOrReRoleAssigned() {
        for (String username : List.of("NXr_CARD_ADMIN_01", "deleted_staff", "admin")) {
            assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest(username, null, "UploadPass1"))).hasMessageContaining("already in use");
        }
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=1", Long.class)).containsExactly(1L);
        assertThat(revoked).isEmpty();
    }
    @Test void duplicateLoginIdentitiesAreExcludedFromManagement() {
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag) VALUES(900,'NXR_CARD_ADMIN_01','duplicate','old','0','0')");
        assertThatThrownBy(() -> reset(50, 101, "NewUploadPass2")).hasMessageContaining("isolated card");
        assertThat(service.list(50, 1, 20, null, null).items()).extracting(AdminCardOperatorService.Operator::userId).containsExactly(102L);
    }
    @Test void unknownPayloadFieldsCannotSetRoleDepartmentPostOrPermissionsEvenWithGlobalJacksonIgnore() {
        for (String key : List.of("roleId", "roleIds", "deptId", "postIds", "permissions", "userId", "status", "createBy")) {
            String body = "{\"username\":\"new_upload_01\",\"nickName\":\"Test\",\"password\":\"UploadPass1\",\"" + key + "\":1}";
            assertThatThrownBy(() -> json.readValue(body, AdminCardOperatorService.CreateRequest.class)).hasMessageContaining("Unsupported account field");
        }
        assertThatThrownBy(() -> json.readValue("{\"password\":\"UploadPass1\",\"roleIds\":[1]}", AdminCardOperatorService.PasswordRequest.class)).hasMessageContaining("Unsupported account field");
        assertThatThrownBy(() -> json.readValue("{\"status\":\"0\",\"roleId\":1}", AdminCardOperatorService.StatusRequest.class)).hasMessageContaining("Unsupported account field");
    }
    @Test void invalidInputsFailBeforeWritingAndPasswordsNeverAppearInErrors() {
        long users = count("sys_user");
        for (String username : List.of("a", "x".repeat(21), "<script>x</script>", "card@other", "a b")) {
            assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest(username, null, "UploadPass1"))).hasMessageContaining("2 to 20");
        }
        assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest("new_upload_01", "<script>alert(1)</script>", "UploadPass1"))).hasMessageContaining("unsupported");
        for (String password : List.of("bad", "abcde", "z".repeat(21), "abc\n123")) {
            assertThatThrownBy(() -> reset(50, 101, password)).hasMessageContaining("6 to 20").hasMessageNotContaining(password);
        }
        assertThatThrownBy(() -> changeStatus(50, 101, "2")).hasMessageContaining("status");
        assertThat(count("sys_user")).isEqualTo(users);
        assertThat(revoked).isEmpty();
    }
    @Test void fiveCharacterPasswordsCannotCreateOrResetAnAccount() {
        long users=count("sys_user"),roles=count("sys_user_role");
        assertThatThrownBy(()->create(50,new AdminCardOperatorService.CreateRequest("boundary_upload",null,"Ab1_Z")))
            .hasMessageContaining("6 to 20").hasMessageNotContaining("Ab1_Z");
        assertThatThrownBy(()->reset(50,101,"Ab1_Z"))
            .hasMessageContaining("6 to 20").hasMessageNotContaining("Ab1_Z");
        assertThat(count("sys_user")).isEqualTo(users);
        assertThat(count("sys_user_role")).isEqualTo(roles);
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=101",String.class)).isEqualTo("old");
        assertThat(revoked).isEmpty();
    }
    @Test void sixCharacterBoundaryWorksForCreationAndPasswordReset() {
        var created=create(50,new AdminCardOperatorService.CreateRequest("boundary_upload",null,"Ab1_-Z"));
        String firstHash=jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=?",String.class,created.userId());
        assertThat(SecurityUtils.matchesPassword("Ab1_-Z",firstHash)).isTrue();
        reset(50,created.userId(),"ZY_1ba");
        String resetHash=jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=?",String.class,created.userId());
        assertThat(SecurityUtils.matchesPassword("ZY_1ba",resetHash)).isTrue();
        assertThat(SecurityUtils.matchesPassword("Ab1_-Z",resetHash)).isFalse();
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?",Long.class,created.userId())).containsExactly(107L);
        assertThat(revoked).containsExactly(created.userId());
    }
    @Test void rollbackKeepsOriginalCredentialsStateRolesAndSessions() {
        assertThatThrownBy(() -> transaction.execute(status -> {
            service.resetPassword(50, 101, new AdminCardOperatorService.PasswordRequest("NewUploadPass2"));
            service.changeStatus(50, 101, new AdminCardOperatorService.StatusRequest("1"));
            assertThat(revoked).isEmpty();
            throw new IllegalStateException("simulate transaction failure");
        })).hasMessageContaining("simulate");
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=101", String.class)).isEqualTo("old");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE user_id=101", String.class)).isEqualTo("0");
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=101", Long.class)).containsExactly(107L);
        assertThat(revoked).isEmpty();
    }
    @Test void insertRoleFailureRollsBackTheNewUserRatherThanLeavingUnprivilegedOrPrivilegedIdentity() {
        jdbc.execute("ALTER TABLE sys_user_role ADD CONSTRAINT reject_new CHECK(user_id<1000)");
        long users = count("sys_user");
        assertThatThrownBy(() -> create(50, new AdminCardOperatorService.CreateRequest("new_upload_01", null, "UploadPass1"))).hasMessageContaining("could not be created");
        assertThat(count("sys_user")).isEqualTo(users);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_name='new_upload_01'", Long.class)).isZero();
    }
    @Test void concurrentDedicatedCreatesAllowOnlyOneCaseInsensitiveIdentity() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> tryCreate(50, "new_upload_01"));
            var second = executor.submit(() -> tryCreate(51, "NEW_UPLOAD_01"));
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE LOWER(user_name)='new_upload_01'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id>=1000", Long.class)).isEqualTo(1);
    }
    @Test void finalScopePredicateRejectsRoleAddedByAnUncoordinatedLegacyWriterAfterChecks() {
        lateScopeChange("INSERT INTO sys_user_role(user_id,role_id) VALUES(101,109)");
        assertThat(jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=101 ORDER BY role_id", Long.class)).containsExactly(107L,109L);
    }
    @Test void finalScopePredicateRejectsLateBusinessBindingWithoutTouchingItsData() {
        lateScopeChange("INSERT INTO commerce_staff_work_center(user_id,work_center_id) VALUES(101,1)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_staff_work_center WHERE user_id=101", Long.class)).isEqualTo(1);
    }
    private void lateScopeChange(String externalSql) {
        // H2 has no InnoDB next-key locks. This real second-connection commit
        // exercises the final UPDATE guard when an inherited legacy transaction
        // uses READ_COMMITTED rather than the normal endpoint's REPEATABLE_READ.
        var injected = new java.util.concurrent.atomic.AtomicBoolean();
        var wrapped = new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection connection = super.getConnection();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
                        && sql.startsWith("UPDATE sys_user u SET password") && injected.compareAndSet(false,true)) {
                        jdbc.update(externalSql); // original DataSource obtains a distinct auto-commit connection
                    }
                    try { return method.invoke(connection,args); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
            }
        };
        JdbcTemplate guardedTemplate = new JdbcTemplate(wrapped);
        CardOperatorSessionInvalidator invalidator = user -> revoked.add(user);
        var guarded = new AdminCardOperatorService(JdbcClient.create(guardedTemplate),guardedTemplate,validators.getValidator(),invalidator);
        var inherited = new TransactionTemplate(new DataSourceTransactionManager(wrapped));
        inherited.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> inherited.execute(status -> guarded.resetPassword(50,101,new AdminCardOperatorService.PasswordRequest("NewUploadPass2"))))
            .hasMessageContaining("no longer an isolated");
        assertThat(injected).isTrue();
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=101",String.class)).isEqualTo("old");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE user_id=101",String.class)).isEqualTo("0");
        assertThat(revoked).isEmpty();
    }
    @Test void mutationsWithoutTransactionalProxyFailBeforeAnyWrite() {
        assertThatThrownBy(() -> service.changeStatus(50, 101, new AdminCardOperatorService.StatusRequest("1"))).hasMessageContaining("requires a transaction");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE user_id=101", String.class)).isEqualTo("0");
    }
    private AdminCardOperatorService.Operator create(long actor, AdminCardOperatorService.CreateRequest request) {
        return transaction.execute(status -> service.create(actor, request));
    }
    private void changeStatus(long actor, long user, String value) {
        transaction.execute(status -> service.changeStatus(actor, user, new AdminCardOperatorService.StatusRequest(value)));
    }
    private void reset(long actor, long user, String password) {
        transaction.execute(status -> service.resetPassword(actor, user, new AdminCardOperatorService.PasswordRequest(password)));
    }
    private boolean tryCreate(long actor, String username) {
        try { create(actor, new AdminCardOperatorService.CreateRequest(username, null, "UploadPass1")); return true; }
        catch (ResponseStatusException error) { assertThat(error.getStatusCode().value()).isEqualTo(409); return false; }
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
