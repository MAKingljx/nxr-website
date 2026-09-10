package com.nxr.platform.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Order-scoped physical-card scanning, label export and packing release gates. */
@Service
public class OrderWorkbenchService {

    private static final Set<String> SCAN_STAGES = Set.of("intake", "label", "packing");
    private static final Set<String> LABEL_READY_STATUSES = Set.of("approved", "published");
    private static final Pattern LABEL_BARCODE_PATTERN = Pattern.compile("^(.+)-L([1-9][0-9]{0,9})-([0-9A-F]{12})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbcClient;

    public OrderWorkbenchService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkbenchSnapshot start(long orderId, long adminUserId) {
        WorkbenchOrder order = lockOrder(orderId);
        if (Set.of("cancelled", "delivered", "payment_exception").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order cannot be opened in the workbench");
        }
        ensurePhysicalItems(order.id());
        WorkbenchSession active = findActiveSession(order.id(), true);
        if (active != null) {
            if (active.lockedByUserId() != adminUserId) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is locked by another workbench user");
            }
            return snapshot(order.id());
        }
        try {
            jdbcClient.sql(
                    """
                    INSERT INTO order_workbench_session
                        (order_id, active_order_id, status_code, locked_by_user_id)
                    VALUES (:orderId, :orderId, 'active', :userId)
                    """
                )
                .param("orderId", order.id())
                .param("userId", adminUserId)
                .update();
        } catch (DataIntegrityViolationException exception) {
            WorkbenchSession winner = findActiveSession(order.id(), true);
            if (winner == null || winner.lockedByUserId() != adminUserId) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is locked by another workbench user", exception);
            }
        }
        return snapshot(order.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkbenchSnapshot scan(long orderId, long adminUserId, ScanRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scan details are required");
        }
        WorkbenchOrder order = lockOrder(orderId);
        WorkbenchSession session = lockSession(order.id(), request.sessionId(), adminUserId);
        String stage = normalizeStage(request.stage());
        String barcode = requireText(request.barcode(), "Barcode", 96).toUpperCase(Locale.ROOT);
        String physicalBarcode = "intake".equals(stage) ? barcode : physicalBarcodeFromLabel(barcode);
        PhysicalItem item = jdbcClient.sql(
                """
                SELECT p.id, p.order_id, p.order_item_id, p.barcode, i.item_no, i.card_name,
                       i.grading_submission_id, s.cert_id, s.product_type_code,
                       s.vintage_classification_code, s.merch_description,
                       g.final_grade_value, g.final_grade_label, s.status_code AS grading_status
                FROM order_physical_item p
                JOIN grading_order_item i ON i.id = p.order_item_id
                LEFT JOIN grading_submission s ON s.id = i.grading_submission_id
                LEFT JOIN grading_score g ON g.submission_id = s.id
                WHERE UPPER(p.barcode) = :barcode
                FOR UPDATE
                """
            )
            .param("barcode", physicalBarcode)
            .query((rs, rowNum) -> new PhysicalItem(
                rs.getLong("id"), rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getString("barcode"),
                rs.getInt("item_no"), rs.getString("card_name"), rs.getObject("grading_submission_id", Long.class),
                rs.getString("cert_id"), rs.getString("product_type_code"),
                rs.getString("vintage_classification_code"), rs.getString("merch_description"),
                rs.getBigDecimal("final_grade_value"), rs.getString("final_grade_label"),
                rs.getString("grading_status")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Physical card barcode not found"));
        if (item.orderId() != order.id()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This physical card belongs to another order");
        }
        if (scanExists(order.id(), item.id(), stage)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card was already scanned for " + stage);
        }
        if ("label".equals(stage)) {
            requireEarlierScan(order.id(), item.id(), "intake");
            assertLabelReady(item);
            assertCurrentPrintedLabelBarcode(order.id(), item, barcode);
        } else if ("packing".equals(stage)) {
            requireEarlierScan(order.id(), item.id(), "label");
            assertCurrentPrintedLabelBarcode(order.id(), item, barcode);
            assertCurrentLabelFingerprint(order.id(), item);
        }
        String labelFingerprint = "label".equals(stage) ? labelFingerprint(item) : null;
        jdbcClient.sql(
                """
                INSERT INTO order_workbench_scan
                    (session_id, order_id, physical_item_id, active_physical_item_id, scan_stage_code,
                     status_code, label_fingerprint, scanned_by_user_id)
                VALUES (:sessionId, :orderId, :itemId, :itemId, :stage, 'active', :fingerprint, :userId)
                """
            )
            .param("sessionId", session.id())
            .param("orderId", order.id())
            .param("itemId", item.id())
            .param("stage", stage)
            .param("fingerprint", labelFingerprint)
            .param("userId", adminUserId)
            .update();
        jdbcClient.sql(
                "UPDATE order_packing_check SET status_code = 'invalidated', invalidated_at = CURRENT_TIMESTAMP "
                    + "WHERE order_id = :orderId AND status_code = 'passed' AND invalidated_at IS NULL"
            )
            .param("orderId", order.id())
            .update();
        return snapshot(order.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkbenchSnapshot completePackingCheck(long orderId, long adminUserId, PackingCheckRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Packing check details are required");
        }
        WorkbenchOrder order = lockOrder(orderId);
        WorkbenchSession session = lockSession(order.id(), request.sessionId(), adminUserId);
        int expected = itemCount(order.id());
        if (expected < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has no physical cards");
        }
        for (String stage : List.of("intake", "label", "packing")) {
            int scanned = stageScanCount(order.id(), stage);
            if (scanned != expected) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Packing check failed: " + (expected - scanned) + " card(s) are missing the " + stage + " scan"
                );
            }
        }
        assertCurrentLabelFingerprints(order.id());
        jdbcClient.sql(
                "UPDATE order_packing_check SET status_code = 'invalidated', invalidated_at = CURRENT_TIMESTAMP "
                    + "WHERE order_id = :orderId AND status_code = 'passed' AND invalidated_at IS NULL"
            )
            .param("orderId", order.id())
            .update();
        jdbcClient.sql(
                """
                INSERT INTO order_packing_check
                    (order_id, session_id, status_code, expected_count, scanned_count, checked_by_user_id)
                VALUES (:orderId, :sessionId, 'passed', :expected, :expected, :userId)
                """
            )
            .param("orderId", order.id())
            .param("sessionId", session.id())
            .param("expected", expected)
            .param("userId", adminUserId)
            .update();
        jdbcClient.sql(
                """
                UPDATE order_workbench_session
                SET status_code = 'closed', active_order_id = NULL, closed_at = CURRENT_TIMESTAMP
                WHERE id = :sessionId AND status_code = 'active'
                """
            )
            .param("sessionId", session.id())
            .update();
        return snapshot(order.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExportFile exportLabels(long orderId, long adminUserId, ExportRequest request) {
        WorkbenchOrder order = lockOrder(orderId);
        ensurePhysicalItems(order.id());
        List<LabelRow> rows = labelRows(order.id());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has no cards to export");
        }
        for (LabelRow row : rows) {
            if (!labelReady(row)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Every card must have one approved grading entry before label export");
            }
            int links = jdbcClient.sql(
                    "SELECT COUNT(*) FROM grading_order_item WHERE grading_submission_id = :submissionId"
                )
                .param("submissionId", row.submissionId())
                .query(Integer.class)
                .single();
            if (links != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A grading entry is linked across multiple order cards");
            }
        }
        String contentFingerprint = orderLabelFingerprint(rows);
        int printSequence = registerPrintJob(
            order.id(), "labels", rows.size(), contentFingerprint,
            request == null ? null : request.reprintReason(), adminUserId
        );
        if (printSequence > 1) {
            invalidateLabelAndPackingScans(order.id());
        }
        StringBuilder csv = new StringBuilder(
            "order_no,item_no,barcode,cert_id,product_type,vintage_classification,merch_description,"
                + "final_grade_value,final_grade_label,card_name\r\n"
        );
        for (LabelRow row : rows) {
            csv.append(csv(row.orderNo())).append(',')
                .append(row.itemNo()).append(',')
                .append(csv(labelBarcode(row.physicalBarcode(), printSequence, labelFingerprint(row)))).append(',')
                .append(csv(row.certId())).append(',')
                .append(csv(productType(row.productType()))).append(',')
                .append(csv(row.vintageClassification())).append(',')
                .append(csv(row.merchDescription())).append(',')
                .append(csv(row.finalGradeValue() == null ? null : row.finalGradeValue().toPlainString())).append(',')
                .append(csv(row.finalGradeLabel())).append(',')
                .append(csv(row.cardName())).append("\r\n");
        }
        return new ExportFile("nxr-" + safeFilename(order.orderNo()) + "-labels.csv", "text/csv;charset=UTF-8", utf8Bom(csv.toString()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExportFile exportManifest(long orderId, long adminUserId, ExportRequest request) {
        WorkbenchOrder order = lockOrder(orderId);
        ensurePhysicalItems(order.id());
        List<WorkbenchItem> rows = loadItems(order.id());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has no cards to export");
        }
        registerPrintJob(
            order.id(), "packing_manifest", rows.size(), null,
            request == null ? null : request.reprintReason(), adminUserId
        );
        StringBuilder csv = new StringBuilder("order_no,item_no,barcode,card_name,cert_id,intake_scanned,label_scanned,packing_scanned\r\n");
        for (WorkbenchItem row : rows) {
            csv.append(csv(order.orderNo())).append(',')
                .append(row.itemNo()).append(',')
                .append(csv(row.barcode())).append(',')
                .append(csv(row.cardName())).append(',')
                .append(csv(row.certId())).append(',')
                .append(row.intakeScannedAt() != null).append(',')
                .append(row.labelScannedAt() != null).append(',')
                .append(row.packingScannedAt() != null).append("\r\n");
        }
        return new ExportFile("nxr-" + safeFilename(order.orderNo()) + "-packing-manifest.csv", "text/csv;charset=UTF-8", utf8Bom(csv.toString()));
    }

    public WorkbenchSnapshot snapshot(long orderId) {
        WorkbenchOrder order = requireOrder(orderId);
        return new WorkbenchSnapshot(
            order.id(), order.orderNo(), order.statusCode(), order.workbenchRequired(),
            findActiveSession(order.id(), false), loadItems(order.id()), latestPackingCheck(order.id()), loadPrintJobs(order.id())
        );
    }

    public void assertIntakeReady(long orderId) {
        WorkbenchOrder order = requireOrder(orderId);
        if (!order.workbenchRequired()) {
            return;
        }
        int expected = itemCount(order.id());
        int scanned = stageScanCount(order.id(), "intake");
        if (expected < 1 || scanned != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scan every physical card into this order before confirming intake");
        }
    }

    public void assertPackingReady(long orderId) {
        WorkbenchOrder order = requireOrder(orderId);
        if (!order.workbenchRequired()) {
            return;
        }
        PackingCheck check = latestPackingCheck(order.id());
        int expected = itemCount(order.id());
        if (check == null || !"passed".equals(check.statusCode()) || check.invalidatedAt() != null
            || expected < 1 || check.expectedCount() != expected || check.scannedCount() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order-scoped packing verification must pass before return shipping");
        }
        assertCurrentLabelFingerprints(order.id());
    }

    public void assertManualStatusTransitionAllowed(long orderId, String targetStatus) {
        if (Set.of("return_shipped", "delivered").contains(clean(targetStatus))) {
            assertPackingReady(orderId);
        }
    }

    private void ensurePhysicalItems(long orderId) {
        List<Long> missing = jdbcClient.sql(
                """
                SELECT i.id FROM grading_order_item i
                LEFT JOIN order_physical_item p ON p.order_item_id = i.id
                WHERE i.order_id = :orderId AND p.id IS NULL
                ORDER BY i.item_no
                """
            )
            .param("orderId", orderId)
            .query(Long.class)
            .list();
        for (Long itemId : missing) {
            boolean inserted = false;
            for (int attempt = 0; attempt < 5 && !inserted; attempt += 1) {
                try {
                    jdbcClient.sql(
                            "INSERT INTO order_physical_item (order_id, order_item_id, barcode) VALUES (:orderId, :itemId, :barcode)"
                        )
                        .param("orderId", orderId)
                        .param("itemId", itemId)
                        .param("barcode", newBarcode())
                        .update();
                    inserted = true;
                } catch (DataIntegrityViolationException exception) {
                    boolean alreadyAllocated = jdbcClient.sql(
                            "SELECT COUNT(*) FROM order_physical_item WHERE order_item_id = :itemId"
                        )
                        .param("itemId", itemId)
                        .query(Integer.class)
                        .single() > 0;
                    if (alreadyAllocated) {
                        inserted = true;
                    } else if (attempt == 4) {
                        throw exception;
                    }
                }
            }
        }
    }

    private WorkbenchOrder lockOrder(long orderId) {
        return orderQuery(orderId, true);
    }

    private WorkbenchOrder requireOrder(long orderId) {
        return orderQuery(orderId, false);
    }

    private WorkbenchOrder orderQuery(long orderId, boolean lock) {
        return jdbcClient.sql(
                "SELECT id, order_no, status_code, workbench_required FROM grading_order WHERE id = :orderId" + (lock ? " FOR UPDATE" : "")
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new WorkbenchOrder(
                rs.getLong("id"), rs.getString("order_no"), rs.getString("status_code"), rs.getBoolean("workbench_required")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private WorkbenchSession lockSession(long orderId, long sessionId, long adminUserId) {
        WorkbenchSession session = jdbcClient.sql(
                """
                SELECT id, order_id, status_code, locked_by_user_id, started_at, closed_at
                FROM order_workbench_session
                WHERE id = :sessionId AND order_id = :orderId
                FOR UPDATE
                """
            )
            .param("sessionId", sessionId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapSession(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workbench session not found"));
        if (!"active".equals(session.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workbench session is closed");
        }
        if (session.lockedByUserId() != adminUserId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is locked by another workbench user");
        }
        return session;
    }

    private WorkbenchSession findActiveSession(long orderId, boolean lock) {
        List<WorkbenchSession> sessions = jdbcClient.sql(
                """
                SELECT id, order_id, status_code, locked_by_user_id, started_at, closed_at
                FROM order_workbench_session
                WHERE active_order_id = :orderId AND status_code = 'active'
                """ + (lock ? " FOR UPDATE" : "")
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapSession(rs))
            .list();
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    private WorkbenchSession mapSession(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkbenchSession(
            rs.getLong("id"), rs.getLong("order_id"), rs.getString("status_code"), rs.getLong("locked_by_user_id"),
            rs.getObject("started_at", LocalDateTime.class), rs.getObject("closed_at", LocalDateTime.class)
        );
    }

    private List<WorkbenchItem> loadItems(long orderId) {
        PrintVersion printVersion = currentLabelPrintVersion(orderId);
        return jdbcClient.sql(
                """
                SELECT p.id, p.order_item_id, p.barcode, i.item_no, i.card_name,
                       i.grading_submission_id, s.status_code AS grading_status,
                       s.cert_id, s.product_type_code, s.vintage_classification_code, s.merch_description,
                       g.final_grade_value, g.final_grade_label,
                       MAX(CASE WHEN sc.scan_stage_code = 'intake' THEN sc.scanned_at END) AS intake_scanned_at,
                       MAX(CASE WHEN sc.scan_stage_code = 'label' THEN sc.scanned_at END) AS label_scanned_at,
                       MAX(CASE WHEN sc.scan_stage_code = 'packing' THEN sc.scanned_at END) AS packing_scanned_at
                FROM order_physical_item p
                JOIN grading_order_item i ON i.id = p.order_item_id
                LEFT JOIN grading_submission s ON s.id = i.grading_submission_id
                LEFT JOIN grading_score g ON g.submission_id = s.id
                LEFT JOIN order_workbench_scan sc ON sc.physical_item_id = p.id AND sc.order_id = p.order_id
                    AND sc.status_code = 'active'
                WHERE p.order_id = :orderId
                GROUP BY p.id, p.order_item_id, p.barcode, i.item_no, i.card_name,
                         i.grading_submission_id, s.status_code,
                         s.cert_id, s.product_type_code, s.vintage_classification_code, s.merch_description,
                         g.final_grade_value, g.final_grade_label
                ORDER BY i.item_no
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> {
                String physicalBarcode = rs.getString("barcode");
                Long submissionId = rs.getObject("grading_submission_id", Long.class);
                String certId = rs.getString("cert_id");
                java.math.BigDecimal finalGradeValue = rs.getBigDecimal("final_grade_value");
                String finalGradeLabel = rs.getString("final_grade_label");
                String cardName = rs.getString("card_name");
                String gradingStatus = rs.getString("grading_status");
                String productType = productType(rs.getString("product_type_code"));
                String vintageClassification = rs.getString("vintage_classification_code");
                String merchDescription = rs.getString("merch_description");
                boolean ready = labelReady(
                    submissionId, certId, productType, vintageClassification, finalGradeValue, gradingStatus
                );
                String currentLabelBarcode = printVersion == null || !ready ? null : labelBarcode(
                    physicalBarcode, printVersion.printSequence(), labelFingerprint(
                        submissionId, certId, productType, vintageClassification, merchDescription,
                        finalGradeValue, finalGradeLabel, cardName
                    )
                );
                return new WorkbenchItem(
                    rs.getLong("id"), rs.getLong("order_item_id"), rs.getInt("item_no"), physicalBarcode,
                    currentLabelBarcode, cardName, submissionId, gradingStatus, certId, productType,
                    vintageClassification, merchDescription, finalGradeValue, finalGradeLabel,
                    rs.getObject("intake_scanned_at", LocalDateTime.class),
                    rs.getObject("label_scanned_at", LocalDateTime.class), rs.getObject("packing_scanned_at", LocalDateTime.class)
                );
            })
            .list();
    }

    private List<LabelRow> labelRows(long orderId) {
        return jdbcClient.sql(
                """
                SELECT o.order_no, i.item_no, p.id AS physical_item_id, p.barcode, i.card_name, i.grading_submission_id,
                       s.cert_id, s.product_type_code, s.vintage_classification_code, s.merch_description,
                       g.final_grade_value, g.final_grade_label, s.status_code AS grading_status
                FROM grading_order o
                JOIN grading_order_item i ON i.order_id = o.id
                JOIN order_physical_item p ON p.order_item_id = i.id AND p.order_id = o.id
                LEFT JOIN grading_submission s ON s.id = i.grading_submission_id
                LEFT JOIN grading_score g ON g.submission_id = s.id
                WHERE o.id = :orderId
                ORDER BY i.item_no
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new LabelRow(
                rs.getString("order_no"), rs.getInt("item_no"), rs.getLong("physical_item_id"),
                rs.getString("barcode"), rs.getString("card_name"),
                rs.getObject("grading_submission_id", Long.class), rs.getString("cert_id"),
                rs.getString("product_type_code"), rs.getString("vintage_classification_code"),
                rs.getString("merch_description"), rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label"), rs.getString("grading_status")
            ))
            .list();
    }

    private PackingCheck latestPackingCheck(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, order_id, session_id, status_code, expected_count, scanned_count,
                       checked_by_user_id, checked_at, invalidated_at
                FROM order_packing_check WHERE order_id = :orderId
                ORDER BY id DESC LIMIT 1
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new PackingCheck(
                rs.getLong("id"), rs.getLong("order_id"), rs.getLong("session_id"), rs.getString("status_code"),
                rs.getInt("expected_count"), rs.getInt("scanned_count"), rs.getLong("checked_by_user_id"),
                rs.getObject("checked_at", LocalDateTime.class), rs.getObject("invalidated_at", LocalDateTime.class)
            ))
            .optional()
            .orElse(null);
    }

    private List<PrintJob> loadPrintJobs(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, export_type_code, print_sequence, row_count, content_fingerprint, reprint_reason,
                       generated_by_user_id, generated_at
                FROM order_print_job WHERE order_id = :orderId ORDER BY id DESC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new PrintJob(
                rs.getLong("id"), rs.getString("export_type_code"), rs.getInt("print_sequence"),
                rs.getInt("row_count"), rs.getString("content_fingerprint"), rs.getString("reprint_reason"), rs.getLong("generated_by_user_id"),
                rs.getObject("generated_at", LocalDateTime.class)
            ))
            .list();
    }

    private int registerPrintJob(
        long orderId, String exportType, int rowCount, String contentFingerprint, String rawReason, long adminUserId
    ) {
        List<Long> existingJobs = jdbcClient.sql(
                "SELECT id FROM order_print_job WHERE order_id = :orderId AND export_type_code = :type ORDER BY id FOR UPDATE"
            )
            .param("orderId", orderId)
            .param("type", exportType)
            .query(Long.class)
            .list();
        int sequence = existingJobs.size() + 1;
        String reason = blankToNull(clean(rawReason, 1000));
        if (sequence > 1 && reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reprint reason is required");
        }
        jdbcClient.sql(
                """
                INSERT INTO order_print_job
                    (order_id, export_type_code, print_sequence, row_count, content_fingerprint,
                     reprint_reason, generated_by_user_id)
                VALUES (:orderId, :type, :sequence, :rowCount, :fingerprint, :reason, :userId)
                """
            )
            .param("orderId", orderId)
            .param("type", exportType)
            .param("sequence", sequence)
            .param("rowCount", rowCount)
            .param("fingerprint", contentFingerprint)
            .param("reason", reason)
            .param("userId", adminUserId)
            .update();
        return sequence;
    }

    private boolean scanExists(long orderId, long itemId, String stage) {
        return jdbcClient.sql(
                "SELECT COUNT(*) FROM order_workbench_scan WHERE order_id = :orderId AND physical_item_id = :itemId "
                    + "AND scan_stage_code = :stage AND status_code = 'active'"
            )
            .param("orderId", orderId)
            .param("itemId", itemId)
            .param("stage", stage)
            .query(Integer.class)
            .single() > 0;
    }

    private void requireEarlierScan(long orderId, long itemId, String stage) {
        if (!scanExists(orderId, itemId, stage)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scan this card for " + stage + " before continuing");
        }
    }

    private void assertLabelReady(PhysicalItem item) {
        if (!labelReady(
            item.submissionId(), item.certId(), item.productType(), item.vintageClassification(),
            item.finalGradeValue(), item.gradingStatus()
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card has no approved grading result for its label");
        }
    }

    private void assertCurrentLabelFingerprint(long orderId, PhysicalItem item) {
        String stored = jdbcClient.sql(
                "SELECT label_fingerprint FROM order_workbench_scan WHERE order_id=:orderId "
                    + "AND physical_item_id=:itemId AND scan_stage_code='label' AND status_code='active'"
            )
            .param("orderId", orderId)
            .param("itemId", item.id())
            .query(String.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This card has no active label scan"));
        assertLabelReady(item);
        if (!labelFingerprint(item).equals(stored)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "The grading result changed after label scanning; re-export and rescan this label"
            );
        }
    }

    private void assertCurrentLabelFingerprints(long orderId) {
        List<LabelRow> rows = labelRows(orderId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has no labeled cards");
        }
        for (LabelRow row : rows) {
            if (!labelReady(row)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Every packed card must keep an approved grading result");
            }
            String stored = jdbcClient.sql(
                    "SELECT label_fingerprint FROM order_workbench_scan WHERE order_id=:orderId "
                        + "AND physical_item_id=:itemId AND scan_stage_code='label' AND status_code='active'"
                )
                .param("orderId", orderId)
                .param("itemId", row.physicalItemId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Every packed card needs an active label scan"));
            String current = labelFingerprint(row);
            if (!current.equals(stored)) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A grading result changed after label scanning; re-export and rescan the affected label"
                );
            }
        }
        requireCurrentLabelPrintVersion(orderId, rows);
    }

    private void assertCurrentPrintedLabelBarcode(long orderId, PhysicalItem item, String scannedBarcode) {
        PrintVersion printVersion = requireCurrentLabelPrintVersion(orderId, labelRows(orderId));
        String expected = labelBarcode(item.barcode(), printVersion.printSequence(), labelFingerprint(item));
        if (!expected.equals(scannedBarcode)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This label barcode is not the current exported version; export and scan the current label"
            );
        }
    }

    private PrintVersion currentLabelPrintVersion(long orderId) {
        List<LabelRow> rows = labelRows(orderId);
        if (rows.isEmpty() || rows.stream().anyMatch(row -> !labelReady(row))) {
            return null;
        }
        PrintVersion latest = latestLabelPrintVersion(orderId);
        return latest != null && orderLabelFingerprint(rows).equals(latest.contentFingerprint()) ? latest : null;
    }

    private PrintVersion requireCurrentLabelPrintVersion(long orderId, List<LabelRow> rows) {
        PrintVersion latest = latestLabelPrintVersion(orderId);
        if (latest == null || !orderLabelFingerprint(rows).equals(latest.contentFingerprint())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "The grading result changed after label export; export the current labels before scanning or shipping"
            );
        }
        return latest;
    }

    private PrintVersion latestLabelPrintVersion(long orderId) {
        return jdbcClient.sql(
                "SELECT print_sequence, content_fingerprint FROM order_print_job "
                    + "WHERE order_id=:orderId AND export_type_code='labels' ORDER BY print_sequence DESC LIMIT 1"
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new PrintVersion(rs.getInt("print_sequence"), rs.getString("content_fingerprint")))
            .optional()
            .orElse(null);
    }

    private void invalidateLabelAndPackingScans(long orderId) {
        jdbcClient.sql(
                "UPDATE order_workbench_scan SET status_code='invalidated', active_physical_item_id=NULL, "
                    + "invalidated_at=CURRENT_TIMESTAMP WHERE order_id=:orderId AND status_code='active' "
                    + "AND scan_stage_code IN ('label','packing')"
            )
            .param("orderId", orderId)
            .update();
        jdbcClient.sql(
                "UPDATE order_packing_check SET status_code='invalidated', invalidated_at=CURRENT_TIMESTAMP "
                    + "WHERE order_id=:orderId AND status_code='passed' AND invalidated_at IS NULL"
            )
            .param("orderId", orderId)
            .update();
    }

    private static String labelFingerprint(PhysicalItem item) {
        return labelFingerprint(
            item.submissionId(), item.certId(), item.productType(), item.vintageClassification(), item.merchDescription(),
            item.finalGradeValue(), item.finalGradeLabel(), item.cardName()
        );
    }

    private static String labelFingerprint(LabelRow row) {
        return labelFingerprint(
            row.submissionId(), row.certId(), row.productType(), row.vintageClassification(), row.merchDescription(),
            row.finalGradeValue(), row.finalGradeLabel(), row.cardName()
        );
    }

    private static String labelFingerprint(
        Long submissionId, String certId, String productType, String vintageClassification, String merchDescription,
        java.math.BigDecimal finalGradeValue, String finalGradeLabel, String cardName
    ) {
        String canonical = String.join("\u001f",
            submissionId == null ? "" : submissionId.toString(),
            certId == null ? "" : certId.trim(),
            productType(productType),
            vintageClassification == null ? "" : vintageClassification.trim(),
            merchDescription == null ? "" : merchDescription.trim(),
            finalGradeValue == null ? "" : finalGradeValue.stripTrailingZeros().toPlainString(),
            finalGradeLabel == null ? "" : finalGradeLabel.trim(),
            cardName == null ? "" : cardName.trim()
        );
        return sha256(canonical);
    }

    private static boolean labelReady(LabelRow row) {
        return labelReady(
            row.submissionId(), row.certId(), row.productType(), row.vintageClassification(),
            row.finalGradeValue(), row.gradingStatus()
        );
    }

    private static boolean labelReady(
        Long submissionId, String certId, String rawProductType, String vintageClassification,
        java.math.BigDecimal finalGradeValue, String gradingStatus
    ) {
        String productType = productType(rawProductType);
        return submissionId != null && certId != null && !certId.isBlank()
            && LABEL_READY_STATUSES.contains(clean(gradingStatus))
            && (!"graded_card".equals(productType) || finalGradeValue != null)
            && (!"vintage_product".equals(productType)
                || (vintageClassification != null && !vintageClassification.isBlank()));
    }

    private static String productType(String value) {
        String normalized = clean(value);
        return Set.of("merch_product", "vintage_product").contains(normalized) ? normalized : "graded_card";
    }

    private static String orderLabelFingerprint(List<LabelRow> rows) {
        StringBuilder canonical = new StringBuilder();
        for (LabelRow row : rows) {
            canonical.append(row.physicalItemId()).append(':').append(labelFingerprint(row)).append('\n');
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String labelBarcode(String physicalBarcode, int printSequence, String fingerprint) {
        return physicalBarcode.toUpperCase(Locale.ROOT) + "-L" + printSequence + "-"
            + fingerprint.substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static String physicalBarcodeFromLabel(String barcode) {
        Matcher matcher = LABEL_BARCODE_PATTERN.matcher(barcode);
        if (!matcher.matches()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Scan the exported label barcode for label and packing stages"
            );
        }
        return matcher.group(1);
    }

    private int itemCount(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM order_physical_item WHERE order_id = :orderId")
            .param("orderId", orderId)
            .query(Integer.class)
            .single();
    }

    private int stageScanCount(long orderId, String stage) {
        return jdbcClient.sql(
                "SELECT COUNT(*) FROM order_workbench_scan WHERE order_id = :orderId AND scan_stage_code = :stage "
                    + "AND status_code = 'active'"
            )
            .param("orderId", orderId)
            .param("stage", stage)
            .query(Integer.class)
            .single();
    }

    private String normalizeStage(String value) {
        String stage = clean(value);
        if (!SCAN_STAGES.contains(stage)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported scan stage");
        }
        return stage;
    }

    private static String newBarcode() {
        byte[] bytes = new byte[15];
        RANDOM.nextBytes(bytes);
        return "NXR-I-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toUpperCase(Locale.ROOT);
    }

    private static byte[] utf8Bom(String value) {
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[content.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(content, 0, withBom, 3, content.length);
        return withBom;
    }

    private static String csv(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String safeFilename(String value) {
        return value == null ? "order" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String requireText(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record WorkbenchOrder(long id, String orderNo, String statusCode, boolean workbenchRequired) {
    }

    private record PhysicalItem(
        long id, long orderId, long orderItemId, String barcode, int itemNo, String cardName,
        Long submissionId, String certId, String productType, String vintageClassification, String merchDescription,
        java.math.BigDecimal finalGradeValue, String finalGradeLabel, String gradingStatus
    ) {
    }

    private record LabelRow(
        String orderNo, int itemNo, long physicalItemId, String physicalBarcode, String cardName, Long submissionId, String certId,
        String productType, String vintageClassification, String merchDescription,
        java.math.BigDecimal finalGradeValue, String finalGradeLabel, String gradingStatus
    ) {
    }

    private record PrintVersion(int printSequence, String contentFingerprint) {
    }

    public record ScanRequest(long sessionId, String stage, String barcode) {
    }

    public record PackingCheckRequest(long sessionId) {
    }

    public record ExportRequest(String reprintReason) {
    }

    public record ExportFile(String filename, String contentType, byte[] content) {
    }

    public record WorkbenchSession(
        long id, long orderId, String statusCode, long lockedByUserId, LocalDateTime startedAt, LocalDateTime closedAt
    ) {
    }

    public record WorkbenchItem(
        long physicalItemId, long orderItemId, int itemNo, String barcode, String labelBarcode, String cardName,
        Long gradingSubmissionId, String gradingStatusCode, String certId, String productType,
        String vintageClassification, String merchDescription,
        java.math.BigDecimal finalGradeValue, String finalGradeLabel,
        LocalDateTime intakeScannedAt, LocalDateTime labelScannedAt, LocalDateTime packingScannedAt
    ) {
    }

    public record PackingCheck(
        long id, long orderId, long sessionId, String statusCode, int expectedCount, int scannedCount,
        long checkedByUserId, LocalDateTime checkedAt, LocalDateTime invalidatedAt
    ) {
    }

    public record PrintJob(
        long id, String exportTypeCode, int printSequence, int rowCount, String contentFingerprint, String reprintReason,
        long generatedByUserId, LocalDateTime generatedAt
    ) {
    }

    public record WorkbenchSnapshot(
        long orderId, String orderNo, String orderStatus, boolean workbenchRequired,
        WorkbenchSession activeSession, List<WorkbenchItem> items, PackingCheck packingCheck, List<PrintJob> printJobs
    ) {
    }
}
