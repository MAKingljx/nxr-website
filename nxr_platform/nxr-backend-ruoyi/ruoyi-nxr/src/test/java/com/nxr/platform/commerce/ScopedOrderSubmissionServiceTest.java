package com.nxr.platform.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nxr.platform.admin.AdminSubmissionService;
import com.nxr.platform.customer.CustomerPortalService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class ScopedOrderSubmissionServiceTest {
    private JdbcClient jdbc;
    private OrderAccessScopeService scope;
    private AdminSubmissionService submissions;
    private CustomerPortalService portal;
    private ScopedOrderSubmissionService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:scoped_entry_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        jdbc = JdbcClient.create(template);
        template.execute("CREATE TABLE grading_order(id BIGINT PRIMARY KEY,order_origin_code VARCHAR(32),business_line_id BIGINT,work_center_id BIGINT)");
        template.execute("CREATE TABLE grading_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,grading_submission_id BIGINT)");
        template.execute("CREATE TABLE grading_submission(id BIGINT PRIMARY KEY,order_origin_code VARCHAR(32),business_line_id BIGINT,work_center_id BIGINT)");
        template.execute("CREATE TABLE order_workbench_session(id BIGINT PRIMARY KEY,order_id BIGINT,active_order_id BIGINT,status_code VARCHAR(16),locked_by_user_id BIGINT)");
        template.execute("CREATE TABLE order_physical_item(id BIGINT PRIMARY KEY,order_id BIGINT,order_item_id BIGINT)");
        template.execute("CREATE TABLE order_workbench_scan(id BIGINT PRIMARY KEY,session_id BIGINT,order_id BIGINT,physical_item_id BIGINT,scan_stage_code VARCHAR(16))");
        jdbc.sql("INSERT INTO grading_order(id,order_origin_code,business_line_id,work_center_id) VALUES(10,'customer_submission',20,30)").update();
        jdbc.sql("INSERT INTO grading_order_item(id,order_id,grading_submission_id) VALUES(11,10,NULL),(12,10,600),(13,10,NULL)").update();
        jdbc.sql("INSERT INTO grading_submission(id) VALUES(500),(600)").update();
        jdbc.sql("INSERT INTO order_workbench_session(id,order_id,active_order_id,status_code,locked_by_user_id) VALUES(70,10,10,'active',7)").update();
        jdbc.sql("INSERT INTO order_physical_item(id,order_id,order_item_id) VALUES(80,10,11),(81,10,12),(82,10,13)").update();
        jdbc.sql("INSERT INTO order_workbench_scan(id,session_id,order_id,physical_item_id,scan_stage_code) VALUES(90,70,10,80,'intake')").update();
        scope = mock(OrderAccessScopeService.class);
        submissions = mock(AdminSubmissionService.class);
        portal = mock(CustomerPortalService.class);
        AdminSubmissionService.SubmissionDetailResponse created = mock(AdminSubmissionService.SubmissionDetailResponse.class);
        when(created.id()).thenReturn(500L);
        when(submissions.createSubmission(any())).thenReturn(created);
        service = new ScopedOrderSubmissionService(jdbc, scope, submissions, portal);
    }

    @Test
    void inheritsLockedOrderRoutingAndIgnoresSuppliedActor() {
        service.create(7, 10, 11, requestWithActor(999));
        verify(scope).requireAccessibleOrder(7, 10);
        ArgumentCaptor<AdminSubmissionService.MutateSubmissionRequest> request = ArgumentCaptor.forClass(AdminSubmissionService.MutateSubmissionRequest.class);
        verify(submissions).createSubmission(request.capture());
        assertEquals(7, request.getValue().actorUserId());
        assertEquals("customer_submission", jdbc.sql("SELECT order_origin_code FROM grading_submission WHERE id=500")
            .query(String.class).single());
        assertEquals(20L, jdbc.sql("SELECT business_line_id FROM grading_submission WHERE id=500")
            .query(Long.class).single());
        assertEquals(30L, jdbc.sql("SELECT work_center_id FROM grading_submission WHERE id=500")
            .query(Long.class).single());
        verify(portal).linkNewOrderItemSubmission(10, 11, 500, 7);
    }

    @Test
    void refusesToReplaceAnExistingItemSubmission() {
        assertThrows(ResponseStatusException.class, () -> service.create(7, 10, 12, requestWithActor(999)));
    }

    @Test
    void requiresTheCurrentWorkbenchOwnerAndAnIntakeScan() {
        assertThrows(ResponseStatusException.class, () -> service.create(8, 10, 11, requestWithActor(999)));
        assertThrows(ResponseStatusException.class, () -> service.create(7, 10, 13, requestWithActor(999)));
    }

    private static AdminSubmissionService.MutateSubmissionRequest requestWithActor(long actor) {
        return new AdminSubmissionService.MutateSubmissionRequest("NXR-TEST", "graded_card", null, null,
            "trading_card", "Card", null, null, null, null, null, null, null, "Brand", null, null,
            "Set", "1", "EN", 1, null, null, null, null, null, actor);
    }
}
