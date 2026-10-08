package com.nxr.platform.admin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Card-business account management. It never grants arbitrary roles, departments or platform permissions. */
@Service
public class AdminCardOperatorService {
    private static final int CARD_PASSWORD_MIN_LENGTH = 6;
    private static final String OPERATOR_ROLE = "nxr_card_manager";
    private static final String MANAGER_ROLE = "nxr_card_super_manager";
    private static final Set<String> CARD_PERMISSIONS = Set.of(
        "nxr:entry:list", "nxr:entry:add", "nxr:entry:edit", "nxr:entry:approve",
        "nxr:media:list", "nxr:media:import", "nxr:media:publish",
        "nxr:brand:list", "nxr:brand:add", "nxr:brand:edit",
        "nxr:export:list", "nxr:export:generate", "nxr:export:remove", "nxr:card:global"
    );
    private static final Set<String> ACCOUNT_PERMISSIONS = Set.of(
        "nxr:card-user:list", "nxr:card-user:add", "nxr:card-user:edit", "nxr:card-user:resetPwd"
    );
    // Interpret DATETIME in its database session zone, then emit an unambiguous browser instant.
    // Decimal scaling also avoids a 32-bit intermediate when a SQL engine returns integer seconds.
    private static final String SUMMARY = "SELECT u.user_id,u.user_name,u.nick_name,u.status,UNIX_TIMESTAMP(u.create_time)*1000.0 AS created_at,UNIX_TIMESTAMP(u.update_time)*1000.0 AS updated_at FROM sys_user u";
    private static final String NO_BUSINESS_BINDINGS = """
        AND NOT EXISTS(SELECT 1 FROM agent_operator_binding b WHERE b.sys_user_id=u.user_id)
        AND NOT EXISTS(SELECT 1 FROM commerce_staff_business_line b WHERE b.user_id=u.user_id)
        AND NOT EXISTS(SELECT 1 FROM commerce_staff_work_center b WHERE b.user_id=u.user_id)
        """;
    private final JdbcClient jdbc;
    private final SimpleJdbcInsert userInsert;
    private final Validator validator;
    private final CardOperatorSessionInvalidator sessions;

    public AdminCardOperatorService(JdbcClient jdbc, JdbcTemplate template, Validator validator,
                                    CardOperatorSessionInvalidator sessions) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.sessions = sessions;
        this.userInsert = new SimpleJdbcInsert(template).withTableName("sys_user")
            .usingGeneratedKeyColumns("user_id")
            .usingColumns("user_name", "nick_name", "password", "user_type", "status", "del_flag",
                          "create_by", "create_time", "pwd_update_date");
    }

    public record Operator(long userId, String userName, String nickName, String status,
                           Long createdAt, Long updatedAt) { }
    public record OperatorPage(List<Operator> items, long total, int page, int pageSize) { }
    public record PasswordReset(long userId, boolean passwordReset) { }
    public record CreateRequest(String username, String nickName,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
        @JsonAnySetter public void rejectField(String name, Object value) { throw bad("Unsupported account field"); }
        @Override public String toString() { return "CreateRequest[credentials omitted]"; }
    }
    public record StatusRequest(String status) {
        @JsonAnySetter public void rejectField(String name, Object value) { throw bad("Unsupported account field"); }
    }
    public record PasswordRequest(@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
        @JsonAnySetter public void rejectField(String name, Object value) { throw bad("Unsupported account field"); }
        @Override public String toString() { return "PasswordRequest[credentials omitted]"; }
    }
    private record Account(long id, String username, String status, String delFlag, String userType) { }
    private record Role(long roleId, String roleKey, String status, String delFlag) { }
    private record Menu(Long menuId, String permission, String status) { }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public OperatorPage list(long actor, int requestedPage, int requestedSize, String query, String status) {
        requireActor(actor, "nxr:card-user:list", false);
        Role role = operatorRole(false);
        String normalizedStatus = status == null || status.isBlank() ? null : status(status);
        String search = optional(query, 100);
        int page = Math.max(1, Math.min(10_000, requestedPage));
        int pageSize = Math.max(1, Math.min(100, requestedSize));
        String where = isolatedOperators();
        Map<String, Object> params = params("role", role.roleId(), "actor", actor);
        if (normalizedStatus != null) {
            where += " AND u.status=:status";
            params.put("status", normalizedStatus);
        }
        if (search != null) {
            where += " AND (LOWER(u.user_name) LIKE :query ESCAPE '!' OR LOWER(u.nick_name) LIKE :query ESCAPE '!')";
            params.put("query", "%" + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM sys_user u" + where).params(params).query(Long.class).single();
        params.put("limit", pageSize);
        params.put("offset", ((long) page - 1) * pageSize);
        List<Operator> items = jdbc.sql(SUMMARY + where + " ORDER BY u.user_id DESC LIMIT :limit OFFSET :offset")
            .params(params).query(Operator.class).list();
        return new OperatorPage(items, total, page, pageSize);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Operator create(long actor, CreateRequest request) {
        requireTransaction();
        // Take the dedicated role mutex before actor rows, so concurrent managers
        // do not deadlock while the current-read username check locks its range.
        Role role = operatorRole(true);
        Account manager = requireActor(actor, "nxr:card-user:add", true);
        if (request == null) throw bad("Account details are required");
        String username = request.username() == null ? "" : request.username().strip();
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,19}")) throw bad("Username must contain 2 to 20 letters, digits, underscores or hyphens");
        String password = password(request.password());
        String nickname = optional(request.nickName(), 30);
        if (nickname == null) nickname = username;
        SysUser candidate = new SysUser();
        candidate.setUserName(username);
        candidate.setNickName(nickname);
        if (!validator.validate(candidate).isEmpty()) throw bad("Account name contains unsupported characters");
        // Serialize this dedicated provisioning path: the legacy sys_user username
        // column has no unique index. Never adopt or re-role an existing account.
        String encodedPassword = SecurityUtils.encryptPassword(password);
        if (usernameCountCurrent(username) != 0) throw conflict("The username is already in use");
        LocalDateTime now = LocalDateTime.now();
        long userId;
        try {
            userId = userInsert.executeAndReturnKey(params("user_name", username, "nick_name", nickname,
                "password", encodedPassword, "user_type", "00", "status", "0", "del_flag", "0",
                "create_by", manager.username(), "create_time", now, "pwd_update_date", now)).longValue();
            jdbc.sql("INSERT INTO sys_user_role(user_id,role_id) VALUES(:user,:role)")
                .params(params("user", userId, "role", role.roleId())).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("The account could not be created; refresh and check its username");
        }
        if (usernameCountCurrent(username) != 1) throw conflict("The username is already in use");
        requireTarget(actor, userId, role, false);
        return summary(userId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Operator changeStatus(long actor, long userId, StatusRequest request) {
        requireTransaction();
        Role role = operatorRole(true);
        Account manager = requireActor(actor, "nxr:card-user:edit", true);
        if (request == null) throw bad("Account status is required");
        String newStatus = status(request.status());
        requireTarget(actor, userId, role, true);
        int changed = jdbc.sql("UPDATE sys_user u SET status=:status,update_by=:actor,update_time=CURRENT_TIMESTAMP WHERE u.user_id=:id" + targetMutationPredicate())
            .params(params("status", newStatus, "actor", manager.username(), "actorId", actor, "id", userId, "role", role.roleId())).update();
        if (changed != 1) throw forbidden("The account is no longer an isolated card upload account");
        sessions.revokeAfterCommit(userId);
        return summary(userId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PasswordReset resetPassword(long actor, long userId, PasswordRequest request) {
        requireTransaction();
        Role role = operatorRole(true);
        Account manager = requireActor(actor, "nxr:card-user:resetPwd", true);
        if (request == null) throw bad("A new password is required");
        String password = password(request.password());
        requireTarget(actor, userId, role, true);
        int changed = jdbc.sql("UPDATE sys_user u SET password=:password,pwd_update_date=CURRENT_TIMESTAMP,update_by=:actor,update_time=CURRENT_TIMESTAMP WHERE u.user_id=:id" + targetMutationPredicate())
            .params(params("password", SecurityUtils.encryptPassword(password), "actor", manager.username(), "actorId", actor, "id", userId, "role", role.roleId())).update();
        if (changed != 1) throw forbidden("The account is no longer an isolated card upload account");
        sessions.revokeAfterCommit(userId);
        return new PasswordReset(userId, true);
    }

    private Account requireActor(long actor, String permission, boolean lock) {
        if (actor <= 0) throw forbidden("A card account manager is required");
        Account account = account(actor, lock);
        if (!"0".equals(account.status()) || !"0".equals(account.delFlag()) || !"00".equals(account.userType()))
            throw forbidden("An active card account manager is required");
        // The platform's one built-in root may oversee the same bounded card
        // business endpoint. It still cannot manage root/manager/mixed targets.
        if (actor == 1) {
            List<Long> links = jdbc.sql("SELECT role_id FROM sys_user_role WHERE user_id=1 ORDER BY role_id" + (lock ? " FOR UPDATE" : ""))
                .query(Long.class).list();
            if (links.isEmpty() || jdbc.sql("SELECT role_id FROM sys_role WHERE role_id IN(:roles) AND role_key='admin' AND status='0' AND del_flag='0'" + (lock ? " FOR UPDATE" : ""))
                .param("roles", links).query(Long.class).list().isEmpty() || usernameCount(account.username()) != 1)
                throw forbidden("An active platform administrator is required");
            return account;
        }
        Role role = pureRole(actor, MANAGER_ROLE, lock);
        List<Menu> menus = requireCardMenus(role.roleId(), true, lock);
        if (menus.stream().noneMatch(menu -> "0".equals(menu.status()) && permission.equals(menu.permission())))
            throw forbidden("This card account action is not allowed");
        if (hasBusinessBinding(actor, lock) || usernameCount(account.username()) != 1)
            throw forbidden("A dedicated card account manager is required");
        return account;
    }

    private void requireTarget(long actor, long target, Role operatorRole, boolean lock) {
        if (target <= 1 || target == actor) throw forbidden("Only card upload accounts can be managed");
        Account account = account(target, lock);
        if (!"0".equals(account.delFlag()) || !"00".equals(account.userType()) || !("0".equals(account.status()) || "1".equals(account.status())))
            throw forbidden("Only card upload accounts can be managed");
        Role role = pureRole(target, OPERATOR_ROLE, lock);
        if (role.roleId() != operatorRole.roleId() || hasBusinessBinding(target, lock) || usernameCount(account.username()) != 1)
            throw forbidden("Only isolated card upload accounts can be managed");
    }

    private Account account(long id, boolean lock) {
        return jdbc.sql("SELECT user_id AS id,user_name AS username,status,del_flag,user_type FROM sys_user WHERE user_id=:id" + (lock ? " FOR UPDATE" : ""))
            .param("id", id).query(Account.class).optional()
            .orElseThrow(() -> forbidden("This backend account cannot be managed here"));
    }

    private Role pureRole(long user, String key, boolean lock) {
        // At REPEATABLE_READ the explicit primary-key prefix range locks all
        // role associations, including the gap in which another role could be added.
        List<Long> links = jdbc.sql("SELECT role_id FROM sys_user_role WHERE user_id=:user ORDER BY role_id" + (lock ? " FOR UPDATE" : ""))
            .param("user", user).query(Long.class).list();
        if (links.size() != 1) throw forbidden("The account has roles outside this card business");
        List<Role> roles = jdbc.sql("SELECT role_id,role_key,status,del_flag FROM sys_role WHERE role_id=:role" + (lock ? " FOR UPDATE" : ""))
            .param("role", links.get(0)).query(Role.class).list();
        if (roles.size() != 1 || !key.equals(roles.get(0).roleKey()) || !"0".equals(roles.get(0).status()) || !"0".equals(roles.get(0).delFlag()))
            throw forbidden("The account has roles outside this card business");
        return roles.get(0);
    }

    private Role operatorRole(boolean lock) {
        List<Role> roles = jdbc.sql("SELECT role_id,role_key,status,del_flag FROM sys_role WHERE role_key=:key ORDER BY role_id" + (lock ? " FOR UPDATE" : ""))
            .param("key", OPERATOR_ROLE).query(Role.class).list();
        if (roles.size() != 1 || !"0".equals(roles.get(0).status()) || !"0".equals(roles.get(0).delFlag()))
            throw conflict("The card upload role is unavailable or ambiguous");
        requireCardMenus(roles.get(0).roleId(), false, lock);
        return roles.get(0);
    }

    private List<Menu> requireCardMenus(long role, boolean manager, boolean lock) {
        List<Menu> menus = jdbc.sql("SELECT m.menu_id,m.perms AS permission,m.status FROM sys_role_menu rm LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=:role ORDER BY rm.menu_id" + (lock ? " FOR UPDATE" : ""))
            .param("role", role).query(Menu.class).list();
        if (menus.isEmpty() || menus.stream().anyMatch(menu -> menu.menuId() == null || (menu.permission() != null && !menu.permission().isBlank()
            && !CARD_PERMISSIONS.contains(menu.permission()) && !(manager && ACCOUNT_PERMISSIONS.contains(menu.permission())))))
            throw forbidden("The card role contains permissions outside this business");
        return menus;
    }

    private boolean hasBusinessBinding(long user, boolean lock) {
        if (lock) {
            // Locking/current reads avoid a prior RR snapshot hiding a binding
            // committed before this target's sys_user row was locked.
            boolean agent = !jdbc.sql("SELECT sys_user_id FROM agent_operator_binding WHERE sys_user_id=:user FOR UPDATE")
                .param("user", user).query(Long.class).list().isEmpty();
            boolean line = !jdbc.sql("SELECT user_id FROM commerce_staff_business_line WHERE user_id=:user ORDER BY business_line_id FOR UPDATE")
                .param("user", user).query(Long.class).list().isEmpty();
            boolean center = !jdbc.sql("SELECT user_id FROM commerce_staff_work_center WHERE user_id=:user ORDER BY work_center_id FOR UPDATE")
                .param("user", user).query(Long.class).list().isEmpty();
            return agent || line || center;
        }
        return jdbc.sql("""
            SELECT (SELECT COUNT(*) FROM agent_operator_binding WHERE sys_user_id=:user)
                 + (SELECT COUNT(*) FROM commerce_staff_business_line WHERE user_id=:user)
                 + (SELECT COUNT(*) FROM commerce_staff_work_center WHERE user_id=:user)
            """).param("user", user).query(Long.class).single() != 0;
    }

    private String targetMutationPredicate() {
        return " AND u.user_id<>1 AND u.user_id<>:actorId AND u.user_type='00' AND u.del_flag='0' AND u.status IN('0','1')"
            + " AND (SELECT COUNT(*) FROM sys_user_role ur WHERE ur.user_id=u.user_id)=1"
            + " AND EXISTS(SELECT 1 FROM sys_user_role ur WHERE ur.user_id=u.user_id AND ur.role_id=:role) " + NO_BUSINESS_BINDINGS;
    }

    private String isolatedOperators() {
        return " WHERE u.user_id<>1 AND u.user_id<>:actor AND u.user_type='00' AND u.del_flag='0' AND u.status IN('0','1')"
            + " AND (SELECT COUNT(*) FROM sys_user_role ur WHERE ur.user_id=u.user_id)=1"
            + " AND EXISTS(SELECT 1 FROM sys_user_role ur WHERE ur.user_id=u.user_id AND ur.role_id=:role)"
            + " AND (SELECT COUNT(*) FROM sys_user other WHERE LOWER(other.user_name)=LOWER(u.user_name))=1 " + NO_BUSINESS_BINDINGS;
    }

    private Operator summary(long user) {
        return jdbc.sql(SUMMARY + " WHERE u.user_id=:id").param("id", user).query(Operator.class).single();
    }
    private long usernameCountCurrent(String username) {
        // A locking/current read must not reuse the transaction snapshot taken
        // before waiting for another manager's successful provisioning commit.
        return jdbc.sql("SELECT user_id FROM sys_user WHERE LOWER(user_name)=:name FOR UPDATE")
            .param("name", username.toLowerCase(Locale.ROOT)).query(Long.class).list().size();
    }
    private long usernameCount(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM sys_user WHERE LOWER(user_name)=:name")
            .param("name", username.toLowerCase(Locale.ROOT)).query(Long.class).single();
    }
    private static String password(String password) {
        if (password == null || password.length() < CARD_PASSWORD_MIN_LENGTH || password.length() > UserConstants.PASSWORD_MAX_LENGTH
            || password.chars().anyMatch(Character::isISOControl)) throw bad("Password must contain 6 to 20 characters");
        return password;
    }
    private static String status(String status) {
        if (!Set.of("0", "1").contains(status == null ? "" : status)) throw bad("Select an active or disabled account status");
        return status;
    }
    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > maximum) throw bad("Account text exceeds the supported length");
        return value.strip();
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Account mutation requires a transaction");
    }
    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
