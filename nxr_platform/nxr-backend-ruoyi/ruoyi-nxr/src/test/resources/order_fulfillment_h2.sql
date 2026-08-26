DROP ALL OBJECTS;

CREATE TABLE customer_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(191) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    mobile VARCHAR(64),
    account_type_code VARCHAR(32) NOT NULL DEFAULT 'customer',
    is_active TINYINT NOT NULL DEFAULT 1,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE grading_submission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cert_id VARCHAR(32) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'pending'
);

CREATE TABLE grading_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    service_level_code VARCHAR(32) NOT NULL,
    return_shipping_option_code VARCHAR(32),
    return_shipping_option_name VARCHAR(128),
    total_card_count INT NOT NULL,
    service_fee DECIMAL(12,2) NOT NULL,
    return_shipping_fee DECIMAL(12,2) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    contact_name VARCHAR(128) NOT NULL,
    contact_phone VARCHAR(64) NOT NULL,
    return_address_line1 VARCHAR(255) NOT NULL,
    return_address_line2 VARCHAR(255),
    return_city VARCHAR(128) NOT NULL,
    return_region VARCHAR(128),
    return_postal_code VARCHAR(64) NOT NULL,
    return_country VARCHAR(128) NOT NULL,
    customer_note TEXT,
    internal_note TEXT,
    intake_code VARCHAR(64),
    packing_slip_code VARCHAR(64),
    shipping_label_created_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE grading_order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    item_no INT NOT NULL,
    card_name VARCHAR(255) NOT NULL,
    brand_name VARCHAR(128),
    set_name VARCHAR(255),
    card_number VARCHAR(128),
    language_code VARCHAR(32),
    declared_value DECIMAL(12,2),
    item_note TEXT,
    status_code VARCHAR(32) NOT NULL,
    grading_submission_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (order_id, item_no)
);

CREATE TABLE payment_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    direction_code VARCHAR(16) NOT NULL,
    payment_type_code VARCHAR(32) NOT NULL,
    payment_no VARCHAR(48),
    provider_code VARCHAR(32) NOT NULL,
    method_label VARCHAR(128),
    status_code VARCHAR(32) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    payer_reference VARCHAR(255),
    proof_reference VARCHAR(512),
    payment_url VARCHAR(512),
    qr_payload VARCHAR(512),
    related_payment_id BIGINT,
    provider_transaction_id VARCHAR(255),
    confirmed_by_user_id BIGINT,
    submitted_at TIMESTAMP,
    confirmed_at TIMESTAMP,
    callback_received_at TIMESTAMP,
    callback_payload TEXT,
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (payment_no)
);

CREATE TABLE payment_callback_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_code VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    payment_id BIGINT,
    payload TEXT NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider_code, provider_event_id)
);

CREATE TABLE order_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    direction_code VARCHAR(16) NOT NULL,
    shipping_option_code VARCHAR(32),
    shipping_option_name VARCHAR(128),
    carrier_name VARCHAR(128) NOT NULL,
    tracking_number VARCHAR(255) NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    shipped_by_user_id BIGINT,
    shipped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP,
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_timeline_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    event_code VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    detail TEXT,
    status_code VARCHAR(32),
    visible_to_customer TINYINT NOT NULL DEFAULT 1,
    actor_type_code VARCHAR(32) NOT NULL,
    actor_customer_id BIGINT,
    actor_admin_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE grading_service_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    price_code VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    is_active TINYINT NOT NULL DEFAULT 1,
    version_no INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE return_shipping_option (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    option_code VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    country_scope VARCHAR(1000) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    price_amount DECIMAL(12,2) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE customer_address (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    label VARCHAR(64) NOT NULL,
    contact_name VARCHAR(128) NOT NULL,
    contact_phone VARCHAR(64) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    city VARCHAR(128) NOT NULL,
    region VARCHAR(128),
    postal_code VARCHAR(64) NOT NULL,
    country VARCHAR(128) NOT NULL,
    is_default TINYINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_intake_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    package_no VARCHAR(128),
    expected_count INT NOT NULL,
    received_count INT NOT NULL,
    condition_note VARCHAR(1000),
    received_by_user_id BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_exception (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    exception_type_code VARCHAR(32) NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    detail TEXT,
    resolution_note TEXT,
    visible_to_customer TINYINT NOT NULL,
    created_by_user_id BIGINT,
    resolved_by_user_id BIGINT,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_work_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT,
    task_type_code VARCHAR(32) NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    result_summary TEXT,
    failure_reason TEXT,
    assigned_user_id BIGINT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE shipment_tracking_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    event_code VARCHAR(32) NOT NULL,
    event_title VARCHAR(255) NOT NULL,
    location_label VARCHAR(255),
    event_detail TEXT,
    event_time TIMESTAMP NOT NULL,
    created_by_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE support_ticket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_no VARCHAR(40) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    category_code VARCHAR(32) NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    assigned_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP
);

CREATE TABLE support_ticket_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    actor_type_code VARCHAR(32) NOT NULL,
    actor_customer_id BIGINT,
    actor_admin_user_id BIGINT,
    message_text TEXT NOT NULL,
    attachment_reference VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE shipping_change_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    ticket_id BIGINT,
    old_option_code VARCHAR(32) NOT NULL,
    old_option_name VARCHAR(128) NOT NULL,
    old_price_amount DECIMAL(12,2) NOT NULL,
    new_option_code VARCHAR(32) NOT NULL,
    new_option_name VARCHAR(128) NOT NULL,
    new_price_amount DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    difference_amount DECIMAL(12,2) NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    reason TEXT,
    payment_id BIGINT,
    reviewed_by_user_id BIGINT,
    reviewed_at TIMESTAMP,
    settled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE merchant_import_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    source_name VARCHAR(255),
    total_rows INT NOT NULL,
    accepted_rows INT NOT NULL DEFAULT 0,
    rejected_rows INT NOT NULL DEFAULT 0,
    status_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE TABLE merchant_import_row (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_job_id BIGINT NOT NULL,
    row_no INT NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    order_id BIGINT,
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (import_job_id, row_no)
);
