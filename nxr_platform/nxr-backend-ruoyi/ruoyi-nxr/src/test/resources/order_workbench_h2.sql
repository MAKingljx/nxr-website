ALTER TABLE grading_order ADD COLUMN workbench_required TINYINT NOT NULL DEFAULT 1;
ALTER TABLE grading_order_item ADD CONSTRAINT uk_workbench_submission UNIQUE (grading_submission_id);

-- Mirrors production: final grades belong to grading_score, not grading_submission.
CREATE TABLE grading_score (
    submission_id BIGINT PRIMARY KEY,
    centering_score DECIMAL(4,1) NOT NULL,
    edges_score DECIMAL(4,1) NOT NULL,
    corners_score DECIMAL(4,1) NOT NULL,
    surface_score DECIMAL(4,1) NOT NULL,
    final_grade_value DECIMAL(4,2) NOT NULL,
    final_grade_label VARCHAR(64) NOT NULL,
    ai_grade_value DECIMAL(4,1),
    ai_centering_score DECIMAL(4,1),
    ai_edges_score DECIMAL(4,1),
    ai_corners_score DECIMAL(4,1),
    ai_surface_score DECIMAL(4,1),
    ai_confidence_value DECIMAL(5,2),
    decision_method_code VARCHAR(32) NOT NULL DEFAULT 'human_only',
    decision_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_physical_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL UNIQUE,
    barcode VARCHAR(48) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_workbench_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    active_order_id BIGINT UNIQUE,
    status_code VARCHAR(16) NOT NULL,
    locked_by_user_id BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP
);

CREATE TABLE order_workbench_scan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    physical_item_id BIGINT NOT NULL,
    active_physical_item_id BIGINT,
    scan_stage_code VARCHAR(16) NOT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'active',
    label_fingerprint CHAR(64),
    scanned_by_user_id BIGINT NOT NULL,
    scanned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invalidated_at TIMESTAMP,
    CONSTRAINT uk_workbench_scan UNIQUE (order_id, active_physical_item_id, scan_stage_code)
);

CREATE TABLE order_print_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    export_type_code VARCHAR(32) NOT NULL,
    print_sequence INT NOT NULL,
    row_count INT NOT NULL,
    content_fingerprint CHAR(64),
    reprint_reason VARCHAR(1000),
    generated_by_user_id BIGINT NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workbench_print UNIQUE (order_id, export_type_code, print_sequence)
);

CREATE TABLE order_packing_check (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    status_code VARCHAR(16) NOT NULL,
    expected_count INT NOT NULL,
    scanned_count INT NOT NULL,
    checked_by_user_id BIGINT NOT NULL,
    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invalidated_at TIMESTAMP
);

INSERT INTO grading_order (
    id, order_no, customer_id, status_code, service_level_code, total_card_count,
    service_fee, return_shipping_fee, total_amount, currency_code, contact_name,
    contact_phone, return_address_line1, return_city, return_postal_code, return_country,
    workbench_required
) VALUES
    (10, 'NXR-WB-10', 1, 'received', 'standard', 2, 20, 10, 30, 'USD', 'Test', '1', '1 Test', 'Test', '1', 'US', 1),
    (20, 'NXR-WB-20', 1, 'received', 'standard', 1, 10, 10, 20, 'USD', 'Test', '1', '1 Test', 'Test', '1', 'US', 1),
    (30, 'NXR-LEGACY-30', 1, 'completed', 'standard', 0, 0, 0, 0, 'USD', 'Test', '1', '1 Test', 'Test', '1', 'US', 0),
    (40, 'NXR-NONGRADED-40', 1, 'received', 'standard', 2, 20, 10, 30, 'USD', 'Test', '1', '1 Test', 'Test', '1', 'US', 1);

INSERT INTO grading_submission (id, cert_id, status_code) VALUES
    (101, 'CERT-101', 'approved'), (102, 'CERT-102', 'published'), (201, 'CERT-201', 'approved');
INSERT INTO grading_submission (
    id, cert_id, product_type_code, vintage_classification_code, merch_description, status_code
) VALUES
    (301, 'CERT-301', 'merch_product', NULL, 'Sealed collectible sticker', 'approved'),
    (302, 'CERT-302', 'vintage_product', 'Legacy', NULL, 'published');
INSERT INTO grading_score (
    submission_id, centering_score, edges_score, corners_score, surface_score,
    final_grade_value, final_grade_label
) VALUES
    (101, 9.5, 9.5, 9.5, 9.5, 9.50, 'Mint 9.5'),
    (102, 8.0, 8.0, 8.0, 8.0, 8.00, 'Excellent 8'),
    (201, 7.0, 7.0, 7.0, 7.0, 7.00, 'Very Good 7');

INSERT INTO grading_order_item (id, order_id, item_no, card_name, status_code, grading_submission_id) VALUES
    (1001, 10, 1, '=Formula-like card', 'grading', 101),
    (1002, 10, 2, 'Second card', 'grading', 102),
    (2001, 20, 1, 'Other order card', 'grading', 201),
    (3001, 40, 1, 'Sticker product', 'grading', 301),
    (3002, 40, 2, 'Vintage product', 'grading', 302);
