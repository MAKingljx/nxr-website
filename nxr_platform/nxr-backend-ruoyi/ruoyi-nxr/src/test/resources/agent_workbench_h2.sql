ALTER TABLE customer_order_photo ADD COLUMN preserved_for_agent TINYINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS agent_client (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_customer_id BIGINT NOT NULL,
    reference VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    phone VARCHAR(64), email VARCHAR(191), contact_name VARCHAR(128),
    address_line1 VARCHAR(255), address_line2 VARCHAR(255), city VARCHAR(128),
    region VARCHAR(128), postal_code VARCHAR(64), country VARCHAR(128), notes VARCHAR(2000),
    active TINYINT NOT NULL DEFAULT 1,
    request_key VARCHAR(128), request_hash CHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (merchant_customer_id, reference),
    UNIQUE (merchant_customer_id, request_key),
    UNIQUE (id, merchant_customer_id),
    CONSTRAINT fk_agent_client_owner FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT ck_agent_client_active CHECK (active IN (0, 1)),
    INDEX ix_agent_client_owner_active (merchant_customer_id, active, id)
);

CREATE TABLE IF NOT EXISTS agent_intake (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_customer_id BIGINT NOT NULL,
    intake_no VARCHAR(48) NOT NULL UNIQUE,
    client_id BIGINT NOT NULL,
    carrier_name VARCHAR(128), tracking_number VARCHAR(255),
    expected_card_count INT NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'expected',
    notes VARCHAR(2000),
    batch_id BIGINT, order_id BIGINT UNIQUE,
    request_key VARCHAR(128), request_hash CHAR(64),
    received_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (merchant_customer_id, request_key),
    UNIQUE (id, merchant_customer_id),
    CONSTRAINT fk_agent_intake_owner FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_agent_intake_client FOREIGN KEY (client_id, merchant_customer_id) REFERENCES agent_client(id, merchant_customer_id),
    CONSTRAINT fk_agent_intake_batch FOREIGN KEY (batch_id) REFERENCES merchant_order_batch(id),
    CONSTRAINT fk_agent_intake_order FOREIGN KEY (order_id) REFERENCES grading_order(id),
    CONSTRAINT ck_agent_intake_count CHECK (expected_card_count BETWEEN 1 AND 1000),
    CONSTRAINT ck_agent_intake_state CHECK (status_code IN ('expected','received','exception','ready','submitted')),
    INDEX ix_agent_intake_owner_client (merchant_customer_id, client_id, status_code, id)
);

CREATE TABLE IF NOT EXISTS agent_return_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_customer_id BIGINT NOT NULL,
    shipment_no VARCHAR(48) NOT NULL UNIQUE,
    client_id BIGINT NOT NULL,
    carrier_name VARCHAR(128) NOT NULL, tracking_number VARCHAR(255) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'shipped',
    contact_name VARCHAR(128) NOT NULL, phone VARCHAR(64) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL, address_line2 VARCHAR(255), city VARCHAR(128) NOT NULL,
    region VARCHAR(128), postal_code VARCHAR(64) NOT NULL, country VARCHAR(128) NOT NULL,
    notes VARCHAR(2000), request_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
    shipped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP,
    UNIQUE (merchant_customer_id, request_key),
    UNIQUE (id, merchant_customer_id),
    CONSTRAINT fk_agent_return_owner FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_agent_return_client FOREIGN KEY (client_id, merchant_customer_id) REFERENCES agent_client(id, merchant_customer_id),
    CONSTRAINT ck_agent_return_state CHECK (status_code IN ('shipped','delivered')),
    INDEX ix_agent_return_owner_client (merchant_customer_id, client_id, status_code, id)
);

CREATE TABLE IF NOT EXISTS agent_card (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_customer_id BIGINT NOT NULL,
    intake_id BIGINT NOT NULL,
    inventory_code VARCHAR(64) NOT NULL UNIQUE, official_card_number VARCHAR(128),
    card_name VARCHAR(255) NOT NULL, language_code VARCHAR(32) NOT NULL, notes VARCHAR(2000),
    status_code VARCHAR(32) NOT NULL DEFAULT 'expected', condition_note VARCHAR(2000),
    front_photo_id BIGINT, back_photo_id BIGINT,
    order_item_id BIGINT UNIQUE,
    return_shipment_id BIGINT,
    checked_in_at TIMESTAMP, returned_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, merchant_customer_id),
    CONSTRAINT fk_agent_card_owner FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_agent_card_intake FOREIGN KEY (intake_id, merchant_customer_id) REFERENCES agent_intake(id, merchant_customer_id),
    CONSTRAINT fk_agent_card_order_item FOREIGN KEY (order_item_id) REFERENCES grading_order_item(id),
    CONSTRAINT fk_agent_card_return FOREIGN KEY (return_shipment_id, merchant_customer_id) REFERENCES agent_return_shipment(id, merchant_customer_id),
    CONSTRAINT fk_agent_card_front FOREIGN KEY (front_photo_id) REFERENCES customer_order_photo(id),
    CONSTRAINT fk_agent_card_back FOREIGN KEY (back_photo_id) REFERENCES customer_order_photo(id),
    CONSTRAINT ck_agent_card_state CHECK (status_code IN ('expected','in_stock','exception','submitted','returned','return_shipped','delivered')),
    INDEX ix_agent_card_owner_state (merchant_customer_id, status_code, intake_id, id)
);

CREATE TABLE IF NOT EXISTS agent_submission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_customer_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL UNIQUE,
    request_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (merchant_customer_id, request_key),
    CONSTRAINT fk_agent_submission_owner FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_agent_submission_batch FOREIGN KEY (batch_id) REFERENCES merchant_order_batch(id)
);

CREATE TABLE IF NOT EXISTS agent_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_customer_id BIGINT NOT NULL,
    client_id BIGINT NOT NULL, intake_id BIGINT, card_id BIGINT, shipment_id BIGINT,
    event_code VARCHAR(48) NOT NULL, note VARCHAR(2000), inventory_code VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_event_owner FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_agent_event_client FOREIGN KEY (client_id, merchant_customer_id) REFERENCES agent_client(id, merchant_customer_id),
    CONSTRAINT fk_agent_event_intake FOREIGN KEY (intake_id, merchant_customer_id) REFERENCES agent_intake(id, merchant_customer_id),
    CONSTRAINT fk_agent_event_card FOREIGN KEY (card_id, merchant_customer_id) REFERENCES agent_card(id, merchant_customer_id),
    CONSTRAINT fk_agent_event_shipment FOREIGN KEY (shipment_id, merchant_customer_id) REFERENCES agent_return_shipment(id, merchant_customer_id),
    INDEX ix_agent_event_owner_client (merchant_customer_id, client_id, id),
    INDEX ix_agent_event_owner_intake (merchant_customer_id, intake_id, id),
    INDEX ix_agent_event_owner_shipment (merchant_customer_id, shipment_id, id)
);

-- Replaying an earlier check-in must not undo a later correction for the same inventory card.
CREATE TABLE IF NOT EXISTS agent_operation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    merchant_customer_id BIGINT NOT NULL,
    operation_code VARCHAR(48) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    target_id BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (merchant_customer_id, operation_code, request_key),
    CONSTRAINT fk_agent_operation_owner FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_agent_operation_intake FOREIGN KEY (target_id, merchant_customer_id) REFERENCES agent_intake(id, merchant_customer_id)
);
