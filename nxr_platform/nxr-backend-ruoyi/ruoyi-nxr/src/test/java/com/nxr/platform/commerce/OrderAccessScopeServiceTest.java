package com.nxr.platform.commerce;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class OrderAccessScopeServiceTest {

    private JdbcClient jdbc;
    private OrderAccessScopeService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:scope_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        jdbc = JdbcClient.create(template);
        template.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(64),nick_name VARCHAR(64),status CHAR(1),del_flag CHAR(1))");
        template.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(64),status CHAR(1),del_flag CHAR(1))");
        template.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        template.execute("CREATE TABLE commerce_business_line(id BIGINT PRIMARY KEY,line_code VARCHAR(48),display_name VARCHAR(128),order_origin_code VARCHAR(32),is_default TINYINT,is_active TINYINT)");
        template.execute("CREATE TABLE commerce_work_center(id BIGINT PRIMARY KEY,center_code VARCHAR(48),display_name VARCHAR(128),is_default TINYINT,is_active TINYINT)");
        template.execute("CREATE TABLE commerce_staff_business_line(user_id BIGINT,business_line_id BIGINT)");
        template.execute("CREATE TABLE commerce_staff_work_center(user_id BIGINT,work_center_id BIGINT)");
        template.execute("CREATE TABLE grading_order(id BIGINT PRIMARY KEY,business_line_id BIGINT,work_center_id BIGINT)");
        template.execute("CREATE TABLE grading_submission(id BIGINT PRIMARY KEY,order_origin_code VARCHAR(32),business_line_id BIGINT,work_center_id BIGINT)");
        template.execute("CREATE TABLE grading_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,grading_submission_id BIGINT)");
        jdbc.sql("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(1,'admin','Admin','0','0'),(7,'operator','Operator','0','0'),(8,'unmapped','Unmapped','0','0')").update();
        jdbc.sql("INSERT INTO sys_role(role_id,role_key,status,del_flag) VALUES(1,'admin','0','0')").update();
        jdbc.sql("INSERT INTO sys_user_role(user_id,role_id) VALUES(1,1)").update();
        jdbc.sql("INSERT INTO commerce_business_line(id,line_code,display_name,order_origin_code,is_default,is_active) VALUES(10,'customer','Customer submissions','customer_submission',1,1),(11,'owned','Owned inventory','owned_inventory',1,1)").update();
        jdbc.sql("INSERT INTO commerce_work_center(id,center_code,display_name,is_default,is_active) VALUES(20,'center-a','Center A',1,1),(21,'center-b','Center B',0,1)").update();
        jdbc.sql("INSERT INTO commerce_staff_business_line(user_id,business_line_id) VALUES(7,10)").update();
        jdbc.sql("INSERT INTO commerce_staff_work_center(user_id,work_center_id) VALUES(7,20)").update();
        jdbc.sql("INSERT INTO grading_order(id,business_line_id,work_center_id) VALUES(100,10,20),(101,10,21),(102,NULL,NULL)").update();
        jdbc.sql("INSERT INTO grading_submission(id,order_origin_code,business_line_id,work_center_id) VALUES(200,NULL,NULL,NULL),(201,'owned_inventory',11,21),(202,NULL,NULL,NULL)").update();
        jdbc.sql("INSERT INTO grading_order_item(id,order_id,grading_submission_id) VALUES(300,100,200)").update();
        service = new OrderAccessScopeService(jdbc, new CommercePolicyService(jdbc));
    }

    @Test
    void requiresBothMappedBusinessLineAndWorkCenter() {
        assertTrue(service.canAccessOrder(7, 100));
        assertFalse(service.canAccessOrder(7, 101));
        assertThrows(ResponseStatusException.class, () -> service.requireAccessibleOrder(7, 101));
    }

    @Test
    void disabledMappedCenterImmediatelyRevokesOrderAccess() {
        assertTrue(service.canAccessOrder(7, 100));
        // A stale mapping row cannot grant access after the center is disabled.
        jdbc.sql("UPDATE commerce_work_center SET is_active=0 WHERE id=20").update();
        assertFalse(service.canAccessOrder(7, 100));
    }

    @Test
    void unmappedStaffFailClosedWhileAdminIsUnrestricted() {
        assertFalse(service.canAccessOrder(8, 100));
        assertTrue(service.scopeForUser(8).denied());
        assertTrue(service.canAccessOrder(1, 102));
    }

    @Test
    void staffAssignmentCanBeReplacedAndCleared() {
        service.saveStaffScope(new OrderAccessScopeService.StaffScopeRequest(7L, List.of(11L), List.of(21L)));
        assertFalse(service.canAccessOrder(7, 100));
        service.saveStaffScope(new OrderAccessScopeService.StaffScopeRequest(7L, List.of(), List.of()));
        assertTrue(service.scopeForUser(7).denied());
    }

    @Test
    void linkedSubmissionUsesOrderScopeAndUnboundInventoryNeedsExplicitAssignment() {
        assertTrue(service.canAccessSubmission(7, 200));
        assertFalse(service.canAccessSubmission(7, 201));
        assertFalse(service.canAccessSubmission(7, 202));
        assertTrue(service.canAccessSubmission(1, 202));
    }

    @Test
    void explicitOwnedInventoryAssignmentCannotOverrideOrderLinkedSubmission() {
        service.assignOwnedInventorySubmission(new OrderAccessScopeService.SubmissionRoutingRequest(202L, 11L, 21L));
        service.saveStaffScope(new OrderAccessScopeService.StaffScopeRequest(7L, List.of(11L), List.of(21L)));
        assertTrue(service.canAccessSubmission(7, 202));
        assertThrows(ResponseStatusException.class, () -> service.assignOwnedInventorySubmission(
            new OrderAccessScopeService.SubmissionRoutingRequest(200L, 11L, 21L)
        ));
    }

    @Test
    void reusableSubmissionListPredicateMatchesSingleItemAuthorization() {
        OrderAccessScopeService.AccessScope scope = service.scopeForUser(7);
        List<Long> visible = jdbc.sql("SELECT s.id FROM grading_submission s WHERE 1=1 "
                + OrderAccessScopeService.submissionSqlPredicate("s") + " ORDER BY s.id")
            .param("scopeLineIds", scope.safeBusinessLineIds())
            .param("scopeCenterIds", scope.safeWorkCenterIds())
            .query(Long.class).list();
        assertTrue(visible.equals(List.of(200L)));
    }
}
