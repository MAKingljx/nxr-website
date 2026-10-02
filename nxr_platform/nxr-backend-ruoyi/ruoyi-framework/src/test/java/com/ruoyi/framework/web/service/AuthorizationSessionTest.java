package com.ruoyi.framework.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.system.domain.SysUserRole;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.AuthorizationCacheService;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import com.ruoyi.system.service.impl.SysRoleServiceImpl;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

public class AuthorizationSessionTest
{
    private static final String PERMISSION = "nxr:brand:list";
    private static final String SECRET = "session-test-secret";
    private JdbcTemplate jdbc;
    private SqlSession session;
    private MemoryRedis redis;
    private AuthorizationCacheService authorizationCache;
    private SysRoleServiceImpl roles;
    private SysUserServiceImpl users;
    private SysMenuServiceImpl menus;
    private SysPermissionService permissions;
    private TokenService tokens;
    private LoginUser login;
    private MockHttpServletRequest request;

    @BeforeEach
    void setup() throws Exception
    {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:auth_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,dept_id BIGINT,user_name VARCHAR(30),nick_name VARCHAR(30),email VARCHAR(50),avatar VARCHAR(100),phonenumber VARCHAR(11),password VARCHAR(100),sex VARCHAR(1),status VARCHAR(1),del_flag VARCHAR(1),login_ip VARCHAR(50),login_date TIMESTAMP,pwd_update_date TIMESTAMP,create_by VARCHAR(30),create_time TIMESTAMP,update_by VARCHAR(30),update_time TIMESTAMP,remark VARCHAR(200))");
        jdbc.execute("CREATE TABLE sys_dept(dept_id BIGINT PRIMARY KEY,parent_id BIGINT,ancestors VARCHAR(100),dept_name VARCHAR(30),order_num INT,leader VARCHAR(30),status VARCHAR(1),del_flag VARCHAR(1))");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_name VARCHAR(30),role_key VARCHAR(100),role_sort INT,data_scope VARCHAR(1),status VARCHAR(1),del_flag VARCHAR(1),menu_check_strictly BOOLEAN,dept_check_strictly BOOLEAN,create_by VARCHAR(30),create_time TIMESTAMP,update_by VARCHAR(30),update_time TIMESTAMP,remark VARCHAR(200))");
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,menu_name VARCHAR(30),parent_id BIGINT,order_num INT,path VARCHAR(100),component VARCHAR(100),`query` VARCHAR(100),route_name VARCHAR(100),is_frame VARCHAR(1),is_cache VARCHAR(1),menu_type VARCHAR(1),visible VARCHAR(1),status VARCHAR(1),perms VARCHAR(100),icon VARCHAR(30),create_time TIMESTAMP,update_time TIMESTAMP,create_by VARCHAR(30),update_by VARCHAR(30),remark VARCHAR(200))");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT,PRIMARY KEY(user_id,role_id))");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");
        jdbc.execute("CREATE TABLE sys_role_dept(role_id BIGINT,dept_id BIGINT)");
        jdbc.execute("CREATE TABLE sys_user_post(user_id BIGINT,post_id BIGINT)");
        jdbc.update("INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) VALUES(42,10,'test-user','Test','0','0')");
        jdbc.update("INSERT INTO sys_dept(dept_id,dept_name,status) VALUES(10,'Test','0')");
        jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,status,del_flag) VALUES(108,'Brand readers','brand-reader',1,'1','0','0')");
        jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,status,perms) VALUES(501,'Brand list','0',?)", PERMISSION);
        jdbc.update("INSERT INTO sys_user_role VALUES(42,108)");
        jdbc.update("INSERT INTO sys_role_menu VALUES(108,501)");

        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.getTypeAliasRegistry().registerAliases("com.ruoyi.common.core.domain.entity");
        cfg.getTypeAliasRegistry().registerAliases("com.ruoyi.system.domain");
        for (String mapper : new String[]{"SysUserMapper", "SysRoleMapper", "SysMenuMapper", "SysUserRoleMapper", "SysRoleMenuMapper", "SysRoleDeptMapper", "SysUserPostMapper"})
        {
            String resource = "mapper/system/" + mapper + ".xml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource))
            {
                // H2 uses CURRENT_TIMESTAMP for MySQL's SYSDATE(); authorization SQL stays intact.
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("sysdate()", "current_timestamp");
                new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(cfg).openSession(true);
        redis = new MemoryRedis();
        authorizationCache = new AuthorizationCacheService();
        ReflectionTestUtils.setField(authorizationCache, "redisCache", redis);
        roles = new SysRoleServiceImpl();
        ReflectionTestUtils.setField(roles, "roleMapper", session.getMapper(SysRoleMapper.class));
        ReflectionTestUtils.setField(roles, "roleMenuMapper", session.getMapper(SysRoleMenuMapper.class));
        ReflectionTestUtils.setField(roles, "roleDeptMapper", session.getMapper(SysRoleDeptMapper.class));
        ReflectionTestUtils.setField(roles, "userRoleMapper", session.getMapper(SysUserRoleMapper.class));
        ReflectionTestUtils.setField(roles, "authorizationCache", authorizationCache);
        users = new SysUserServiceImpl();
        ReflectionTestUtils.setField(users, "userMapper", session.getMapper(SysUserMapper.class));
        ReflectionTestUtils.setField(users, "userRoleMapper", session.getMapper(SysUserRoleMapper.class));
        ReflectionTestUtils.setField(users, "userPostMapper", session.getMapper(SysUserPostMapper.class));
        ReflectionTestUtils.setField(users, "authorizationCache", authorizationCache);
        menus = new SysMenuServiceImpl();
        ReflectionTestUtils.setField(menus, "menuMapper", session.getMapper(SysMenuMapper.class));
        ReflectionTestUtils.setField(menus, "authorizationCache", authorizationCache);
        permissions = new SysPermissionService();
        ReflectionTestUtils.setField(permissions, "roleService", roles);
        ReflectionTestUtils.setField(permissions, "menuService", menus);
        tokens = new TokenService();
        ReflectionTestUtils.setField(tokens, "redisCache", redis);
        ReflectionTestUtils.setField(tokens, "authorizationCache", authorizationCache);
        ReflectionTestUtils.setField(tokens, "userService", users);
        ReflectionTestUtils.setField(tokens, "permissionService", permissions);
        ReflectionTestUtils.setField(tokens, "header", "Authorization");
        ReflectionTestUtils.setField(tokens, "secret", SECRET);
        ReflectionTestUtils.setField(tokens, "expireTime", 30);
        ReflectionTestUtils.setField(tokens, "rememberExpireTime", 43200);
        SysUser user = users.selectUserById(42L);
        login = new LoginUser(42L, 10L, user, permissions.getMenuPermission(user));
        login.setToken("test-token");
        login.setLoginTime(System.currentTimeMillis());
        login.setExpireTime(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(29));
        login.setRememberMe(true);
        redis.values.put(CacheConstants.LOGIN_TOKEN_KEY + login.getToken(), login);
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        request.addHeader("Authorization", "Bearer " + Jwts.builder().claim(Constants.LOGIN_USER_KEY, login.getToken()).signWith(SignatureAlgorithm.HS512, SECRET).compact());
    }

    @AfterEach
    void cleanup()
    {
        session.close();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void disabledAndEnabledRolesTakeEffectOnExistingRememberedSessionWithoutExtendingTtl()
    {
        long expiry = login.getExpireTime();
        assertThat(tokens.getLoginUser(request).getPermissions()).contains(PERMISSION);
        SysRole role = new SysRole(108L);
        role.setStatus("1");
        roles.updateRoleStatus(role);
        assertThat(tokens.getLoginUser(request).getPermissions()).doesNotContain(PERMISSION);
        assertThat(login.getUser().getRoles()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo("1");
            assertThat(r.getPermissions()).isEmpty();
        });
        assertThat(permissions.getRolePermission(login.getUser())).doesNotContain("brand-reader");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null));
        PermissionService guard = new PermissionService();
        assertThat(guard.hasRole("brand-reader")).isFalse();
        assertThat(guard.hasAnyRoles("brand-reader,other-role")).isFalse();
        assertThat(guard.hasPermi(PERMISSION)).isFalse();
        assertThat(login.getExpireTime()).isEqualTo(expiry);
        assertThat(redis.lastTimeoutUnit).isEqualTo(TimeUnit.SECONDS);
        assertThat(redis.lastTimeout).isBetween(28 * 86400, 29 * 86400);
        assertThat(login.isRememberMe()).isTrue();
        role.setStatus("0");
        roles.updateRoleStatus(role);
        assertThat(tokens.getLoginUser(request).getPermissions()).contains(PERMISSION);
        assertThat(guard.hasRole("brand-reader")).isTrue();
        assertThat(guard.hasPermi(PERMISSION)).isTrue();
    }

    @Test
    void singleAndBatchRoleRevocationAndGrantUpdateOldSession()
    {
        tokens.getLoginUser(request);
        SysUserRole binding = new SysUserRole();
        binding.setUserId(42L);
        binding.setRoleId(108L);
        roles.deleteAuthUser(binding);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
        roles.insertAuthUsers(108L, new Long[]{42L});
        assertThat(tokens.getLoginUser(request).getPermissions()).contains(PERMISSION);
        roles.deleteAuthUsers(108L, new Long[]{42L});
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
        users.insertUserAuth(42L, new Long[]{108L});
        assertThat(tokens.getLoginUser(request).getPermissions()).contains(PERMISSION);
        users.insertUserAuth(42L, new Long[]{});
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
    }

    @Test
    void userEditReplacesRoleBindingsImmediately()
    {
        tokens.getLoginUser(request);
        SysUser edit = new SysUser(42L);
        edit.setRoleIds(new Long[]{});
        users.updateUser(edit);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
        edit.setRoleIds(new Long[]{108L});
        users.updateUser(edit);
        assertThat(tokens.getLoginUser(request).getPermissions()).contains(PERMISSION);
    }

    @Test
    void roleMenuEditDataScopeAndDeletionReloadCurrentRoles()
    {
        tokens.getLoginUser(request);
        SysRole role = new SysRole(108L);
        role.setMenuIds(new Long[]{});
        roles.updateRole(role);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
        role.setMenuIds(new Long[]{501L});
        roles.updateRole(role);
        assertThat(tokens.getLoginUser(request).getPermissions()).contains(PERMISSION);
        role.setDataScope("5");
        role.setDeptIds(new Long[]{});
        roles.authDataScope(role);
        assertThat(tokens.getLoginUser(request).getUser().getRoles()).singleElement().extracting(SysRole::getDataScope).isEqualTo("5");
        roles.deleteRoleById(108L);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
        assertThat(login.getUser().getRoles()).isEmpty();
    }

    @Test
    void disabledRenamedAndDeletedMenusDropStalePermissions()
    {
        tokens.getLoginUser(request);
        SysMenu menu = new SysMenu();
        menu.setMenuId(501L);
        menu.setStatus("1");
        menus.updateMenu(menu);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
        menu.setStatus("0");
        menu.setPerms("nxr:brand:edit");
        menus.updateMenu(menu);
        assertThat(tokens.getLoginUser(request).getPermissions()).containsExactly("nxr:brand:edit");
        menus.deleteMenuById(501L);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
    }

    @Test
    void legacyTokenWithoutRevisionCannotKeepDisabledRolePermissions()
    {
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=108");
        session.clearCache();
        assertThat(login.getAuthorizationRevision()).isNull();
        assertThat(login.getPermissions()).contains(PERMISSION);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
    }

    @Test
    void disabledAndDeletedUsersLoseTheirExistingSession()
    {
        tokens.getLoginUser(request);
        SysUser user = new SysUser(42L);
        user.setStatus("1");
        users.updateUserStatus(user);
        assertThat(tokens.getLoginUser(request)).isNull();
        assertThat(redis.values).doesNotContainKey(CacheConstants.LOGIN_TOKEN_KEY + login.getToken());
    }

    @Test
    void deletedUserCannotReuseRememberedToken()
    {
        tokens.getLoginUser(request);
        users.deleteUserById(42L);
        assertThat(tokens.getLoginUser(request)).isNull();
    }

    @Test
    void unchangedRevisionUsesCachedAuthorizationAndNormalSlidingRenewalStillWorks()
    {
        tokens.getLoginUser(request);
        int writes = redis.sessionWrites;
        tokens.getLoginUser(request);
        assertThat(redis.sessionWrites).isEqualTo(writes);
        login.setExpireTime(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5));
        tokens.verifyToken(login);
        assertThat(redis.lastTimeout).isEqualTo(43200);
        assertThat(redis.lastTimeoutUnit).isEqualTo(TimeUnit.MINUTES);
        assertThat(login.getExpireTime() - System.currentTimeMillis()).isCloseTo(TimeUnit.DAYS.toMillis(30), org.assertj.core.data.Offset.offset(2000L));
        login.setRememberMe(false);
        login.setExpireTime(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5));
        tokens.verifyToken(login);
        assertThat(redis.lastTimeout).isEqualTo(30);
        assertThat(login.getExpireTime() - System.currentTimeMillis()).isCloseTo(TimeUnit.MINUTES.toMillis(30), org.assertj.core.data.Offset.offset(2000L));
    }

    @Test
    void disablingOneRoleKeepsOtherActiveRoleAndAdminRetainsWildcardPermission()
    {
        jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,status,del_flag) VALUES(109,'Other readers','other-reader',2,'5','0','0')");
        jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,status,perms) VALUES(502,'Other list','0','nxr:other:list')");
        jdbc.update("INSERT INTO sys_role_menu VALUES(109,502)");
        roles.insertAuthUsers(109L, new Long[]{42L});
        assertThat(tokens.getLoginUser(request).getPermissions()).contains(PERMISSION, "nxr:other:list");
        SysRole disabled = new SysRole(108L);
        disabled.setStatus("1");
        roles.updateRoleStatus(disabled);
        assertThat(tokens.getLoginUser(request).getPermissions()).containsExactly("nxr:other:list");
        SysUser admin = new SysUser(1L);
        assertThat(permissions.getMenuPermission(admin)).containsExactly(Constants.ALL_PERMISSION);
    }

    @Test
    void newlyCreatedRegularAndRememberedTokensKeepTheConfiguredLifetime()
    {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tokens.createToken(login, true);
        assertThat(login.getAuthorizationRevision()).isNull();
        assertThat(login.isRememberMe()).isTrue();
        assertThat(redis.lastTimeout).isEqualTo(43200);
        tokens.createToken(login, false);
        assertThat(login.isRememberMe()).isFalse();
        assertThat(redis.lastTimeout).isEqualTo(30);
    }

    @Test
    void staleConcurrentSessionWriteCannotHideCommittedRevocation()
    {
        tokens.getLoginUser(request);
        LoginUser inFlight = new LoginUser(42L, login.getDeptId(), login.getUser(), login.getPermissions());
        inFlight.setToken(login.getToken());
        inFlight.setAuthorizationRevision(login.getAuthorizationRevision());
        inFlight.setRememberMe(true);
        users.insertUserAuth(42L, new Long[]{});
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
        // A request authenticated before revocation may finish and write its old snapshot later.
        tokens.refreshToken(inFlight);
        assertThat(tokens.getLoginUser(request).getPermissions()).isEmpty();
    }

    @Test
    void expiredTokenCannotBeRevivedByPermissionRefresh()
    {
        login.setExpireTime(System.currentTimeMillis() - 1000);
        authorizationCache.invalidate();
        assertThat(tokens.getLoginUser(request)).isNull();
    }

    static class MemoryRedis extends RedisCache
    {
        final Map<String, Object> values = new HashMap<>();
        int lastTimeout;
        int sessionWrites;
        TimeUnit lastTimeoutUnit;
        @Override @SuppressWarnings("unchecked")
        public <T> T getCacheObject(String key) { return (T) values.get(key); }
        @Override
        public <T> void setCacheObject(String key, T value) { values.put(key, value); }
        @Override
        public <T> void setCacheObject(String key, T value, Integer timeout, TimeUnit unit)
        {
            values.put(key, value);
            lastTimeout = timeout;
            lastTimeoutUnit = unit;
            sessionWrites++;
        }
        @Override
        public boolean deleteObject(String key) { return values.remove(key) != null; }
    }
}
