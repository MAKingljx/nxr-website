-- Card-granular NXR-to-partner parcels. Additive; no existing business rows are rewritten.
CREATE TABLE IF NOT EXISTS partner_return_card_check (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_item_id BIGINT NOT NULL,
 content_fingerprint CHAR(64) NOT NULL, checked_by_user_id BIGINT NOT NULL,
 note VARCHAR(2000), checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_partner_check_item (order_item_id,id),
 CONSTRAINT fk_partner_check_item FOREIGN KEY(order_item_id) REFERENCES grading_order_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS partner_return_parcel (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, parcel_no VARCHAR(48) NOT NULL UNIQUE,
 merchant_customer_id BIGINT NOT NULL, carrier_name VARCHAR(128) NOT NULL, tracking_number VARCHAR(255) NOT NULL,
 status_code VARCHAR(16) NOT NULL DEFAULT 'shipped', return_address TEXT NOT NULL, address_fingerprint CHAR(64) NOT NULL,
 request_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, note VARCHAR(2000), created_by_user_id BIGINT NOT NULL,
 shipped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP NULL, delivered_by_user_id BIGINT NULL,
 UNIQUE KEY uk_partner_return_request (merchant_customer_id, request_key),
 UNIQUE KEY uk_partner_return_tracking (carrier_name,tracking_number),
 INDEX ix_partner_return_owner (merchant_customer_id,status_code,id),
 CONSTRAINT fk_partner_return_owner FOREIGN KEY(merchant_customer_id) REFERENCES customer_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS partner_return_item (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, parcel_id BIGINT NOT NULL, order_item_id BIGINT NOT NULL UNIQUE,
 check_id BIGINT NOT NULL, cert_id VARCHAR(32) NOT NULL, content_fingerprint CHAR(64) NOT NULL,
 INDEX ix_partner_return_parcel (parcel_id,order_item_id),
 CONSTRAINT fk_partner_return_parcel FOREIGN KEY(parcel_id) REFERENCES partner_return_parcel(id),
 CONSTRAINT fk_partner_return_item FOREIGN KEY(order_item_id) REFERENCES grading_order_item(id),
 CONSTRAINT fk_partner_return_check FOREIGN KEY(check_id) REFERENCES partner_return_card_check(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS order_card_label_print (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, physical_item_id BIGINT NOT NULL,
 print_sequence INT NOT NULL, content_fingerprint CHAR(64) NOT NULL,
 UNIQUE KEY uk_order_card_label_version (physical_item_id,print_sequence),
 CONSTRAINT fk_card_label_order FOREIGN KEY(order_id) REFERENCES grading_order(id),
 CONSTRAINT fk_card_label_physical FOREIGN KEY(physical_item_id) REFERENCES order_physical_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
