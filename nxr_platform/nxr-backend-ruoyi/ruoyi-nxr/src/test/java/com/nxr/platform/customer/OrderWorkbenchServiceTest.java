package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

class OrderWorkbenchServiceTest {

    private JdbcTemplate jdbc;
    private JdbcClient jdbcClient;
    private OrderWorkbenchService service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_order_workbench;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("order_fulfillment_h2.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("order_workbench_h2.sql"));
        }
        jdbcClient = JdbcClient.create(jdbc);
        service = new OrderWorkbenchService(jdbcClient);
    }

    @Test
    void rejectsCrossOrderDuplicateAndMissingScansBeforePackingRelease() {
        OrderWorkbenchService.WorkbenchSnapshot first = service.start(10, 700);
        OrderWorkbenchService.WorkbenchSnapshot second = service.start(20, 701);
        String firstBarcode = first.items().get(0).barcode();
        String otherOrderBarcode = second.items().get(0).barcode();

        assertThat(first.items()).hasSize(2);
        assertThat(first.items()).extracting(OrderWorkbenchService.WorkbenchItem::barcode).doesNotHaveDuplicates();
        assertThatThrownBy(() -> service.scan(10, 700,
            new OrderWorkbenchService.ScanRequest(first.activeSession().id(), "intake", otherOrderBarcode)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("another order");

        service.scan(10, 700, new OrderWorkbenchService.ScanRequest(first.activeSession().id(), "intake", firstBarcode));
        assertThatThrownBy(() -> service.scan(10, 700,
            new OrderWorkbenchService.ScanRequest(first.activeSession().id(), "intake", firstBarcode)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already scanned");
        assertThatThrownBy(() -> service.completePackingCheck(10, 700,
            new OrderWorkbenchService.PackingCheckRequest(first.activeSession().id())))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> service.assertManualStatusTransitionAllowed(10, "return_shipped"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("packing verification");
        assertThatThrownBy(() -> service.assertManualStatusTransitionAllowed(10, "delivered"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("packing verification");
        jdbc.update("UPDATE grading_order SET status_code='completed' WHERE id=10");
        OrderFulfillmentService fulfillment = new OrderFulfillmentService(jdbcClient, jdbc, null, service);
        assertThatThrownBy(() -> fulfillment.assertOutboundReady(10))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("packing verification");
        service.assertManualStatusTransitionAllowed(30, "return_shipped");
    }

    @Test
    void exportsOrderScopedLabelsAndRecordsReasonedReprintsAfterAllChecks() {
        OrderWorkbenchService.WorkbenchSnapshot started = service.start(10, 700);
        for (OrderWorkbenchService.WorkbenchItem item : started.items()) {
            service.scan(10, 700, new OrderWorkbenchService.ScanRequest(started.activeSession().id(), "intake", item.barcode()));
        }
        assertThatThrownBy(() -> service.scan(10, 700,
            new OrderWorkbenchService.ScanRequest(started.activeSession().id(), "label", started.items().get(0).barcode())))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("exported label barcode");
        String csv = new String(service.exportLabels(10, 700, null).content(), StandardCharsets.UTF_8);
        OrderWorkbenchService.WorkbenchSnapshot snapshot = service.snapshot(10);
        assertThat(csv).contains("order_no,item_no,barcode,cert_id,product_type,vintage_classification,merch_description,final_grade_value,final_grade_label,card_name")
            .contains("NXR-WB-10").contains("CERT-101").contains("CERT-102")
            .contains("'=Formula-like card").doesNotContain("CERT-201")
            .contains(snapshot.items().get(0).labelBarcode());
        assertThat(snapshot.items()).extracting(OrderWorkbenchService.WorkbenchItem::labelBarcode)
            .doesNotContainNull().doesNotHaveDuplicates();
        for (OrderWorkbenchService.WorkbenchItem item : snapshot.items()) {
            service.scan(10, 700, new OrderWorkbenchService.ScanRequest(snapshot.activeSession().id(), "label", item.labelBarcode()));
            service.scan(10, 700, new OrderWorkbenchService.ScanRequest(snapshot.activeSession().id(), "packing", item.labelBarcode()));
        }
        OrderWorkbenchService.WorkbenchSnapshot packed = service.completePackingCheck(
            10, 700, new OrderWorkbenchService.PackingCheckRequest(snapshot.activeSession().id()));
        assertThat(packed.activeSession()).isNull();
        assertThat(packed.packingCheck().statusCode()).isEqualTo("passed");
        service.assertManualStatusTransitionAllowed(10, "return_shipped");
        jdbc.update("UPDATE grading_order SET status_code='completed' WHERE id=10");
        OrderFulfillmentService fulfillment = new OrderFulfillmentService(jdbcClient, jdbc, null, service);
        fulfillment.assertOutboundReady(10);
        List<String> oldLabelBarcodes = service.snapshot(10).items().stream()
            .map(OrderWorkbenchService.WorkbenchItem::labelBarcode).toList();

        jdbc.update("UPDATE grading_score SET final_grade_value=9.00,final_grade_label='Mint 9' WHERE submission_id=101");
        assertThatThrownBy(() -> fulfillment.assertOutboundReady(10))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("changed after label scanning");
        assertThatThrownBy(() -> service.exportLabels(10, 700, null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("reprint reason");
        service.exportLabels(10, 700, new OrderWorkbenchService.ExportRequest("Damaged first sheet"));
        OrderWorkbenchService.WorkbenchSnapshot invalidated = service.snapshot(10);
        assertThat(invalidated.items()).allSatisfy(item -> {
            assertThat(item.intakeScannedAt()).isNotNull();
            assertThat(item.labelScannedAt()).isNull();
            assertThat(item.packingScannedAt()).isNull();
        });
        assertThat(invalidated.items()).extracting(OrderWorkbenchService.WorkbenchItem::labelBarcode)
            .doesNotContainAnyElementsOf(oldLabelBarcodes);
        OrderWorkbenchService.WorkbenchSnapshot reopened = service.start(10, 700);
        assertThatThrownBy(() -> service.scan(10, 700,
            new OrderWorkbenchService.ScanRequest(reopened.activeSession().id(), "label", oldLabelBarcodes.get(0))))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not the current exported version");
        for (OrderWorkbenchService.WorkbenchItem item : reopened.items()) {
            service.scan(10, 700, new OrderWorkbenchService.ScanRequest(reopened.activeSession().id(), "label", item.labelBarcode()));
            service.scan(10, 700, new OrderWorkbenchService.ScanRequest(reopened.activeSession().id(), "packing", item.labelBarcode()));
        }
        service.completePackingCheck(10, 700, new OrderWorkbenchService.PackingCheckRequest(reopened.activeSession().id()));
        fulfillment.assertOutboundReady(10);
        assertThat(service.snapshot(10).printJobs()).extracting(OrderWorkbenchService.PrintJob::printSequence)
            .containsExactly(2, 1);
    }

    @Test
    void preventsTwoOrdersFromSharingOneGradingSubmissionAndKeepsLockOwner() {
        OrderWorkbenchService.WorkbenchSnapshot first = service.start(10, 700);
        assertThatThrownBy(() -> service.start(10, 701))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("another workbench user");
        assertThat(service.start(10, 700).activeSession().id()).isEqualTo(first.activeSession().id());

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE grading_order_item SET grading_submission_id=101 WHERE id=2001"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void labelsAndPacksApprovedMerchAndVintageProductsWithoutNumericGrades() {
        OrderWorkbenchService.WorkbenchSnapshot started = service.start(40, 700);
        for (OrderWorkbenchService.WorkbenchItem item : started.items()) {
            service.scan(40, 700, new OrderWorkbenchService.ScanRequest(started.activeSession().id(), "intake", item.barcode()));
        }
        String csv = new String(service.exportLabels(40, 700, null).content(), StandardCharsets.UTF_8);
        OrderWorkbenchService.WorkbenchSnapshot labeled = service.snapshot(40);
        assertThat(csv).contains("merch_product").contains("Sealed collectible sticker")
            .contains("vintage_product").contains("Legacy");
        assertThat(labeled.items()).extracting(OrderWorkbenchService.WorkbenchItem::finalGradeValue).containsOnlyNulls();
        assertThat(labeled.items()).extracting(OrderWorkbenchService.WorkbenchItem::labelBarcode).doesNotContainNull();
        for (OrderWorkbenchService.WorkbenchItem item : labeled.items()) {
            service.scan(40, 700, new OrderWorkbenchService.ScanRequest(labeled.activeSession().id(), "label", item.labelBarcode()));
            service.scan(40, 700, new OrderWorkbenchService.ScanRequest(labeled.activeSession().id(), "packing", item.labelBarcode()));
        }
        service.completePackingCheck(40, 700, new OrderWorkbenchService.PackingCheckRequest(labeled.activeSession().id()));
        service.assertPackingReady(40);
    }
}
