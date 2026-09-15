-- Projection fixtures intentionally allow bad historical owner links so read guards are exercised.
CREATE TABLE agent_client (
 id BIGINT PRIMARY KEY, merchant_customer_id BIGINT, reference VARCHAR(64), display_name VARCHAR(128),
 phone VARCHAR(64), email VARCHAR(191), active TINYINT, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_intake (
 id BIGINT PRIMARY KEY, merchant_customer_id BIGINT, client_id BIGINT, intake_no VARCHAR(48),
 carrier_name VARCHAR(128), tracking_number VARCHAR(255), expected_card_count INT, status_code VARCHAR(32),
 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_return_shipment (
 id BIGINT PRIMARY KEY, merchant_customer_id BIGINT, client_id BIGINT, shipment_no VARCHAR(48),
 carrier_name VARCHAR(128), tracking_number VARCHAR(255), status_code VARCHAR(32), shipped_at TIMESTAMP,
 delivered_at TIMESTAMP
);
CREATE TABLE agent_card (
 id BIGINT PRIMARY KEY, merchant_customer_id BIGINT, intake_id BIGINT, return_shipment_id BIGINT
);
CREATE TABLE merchant_order_batch (
 id BIGINT PRIMARY KEY, merchant_customer_id BIGINT, batch_no VARCHAR(48), batch_name VARCHAR(191),
 source_name VARCHAR(255), status_code VARCHAR(32), updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE merchant_order_batch_item (id BIGINT PRIMARY KEY,batch_id BIGINT,order_id BIGINT);
CREATE TABLE grading_order (id BIGINT PRIMARY KEY,customer_id BIGINT,total_card_count INT);
CREATE TABLE merchant_wallet (
 id BIGINT PRIMARY KEY,customer_id BIGINT,currency_code VARCHAR(8),balance DECIMAL(18,2),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE customer_address (
 id BIGINT PRIMARY KEY,customer_id BIGINT,label VARCHAR(64),contact_name VARCHAR(128),
 address_line1 VARCHAR(255),address_line2 VARCHAR(255),city VARCHAR(128),region VARCHAR(128),
 postal_code VARCHAR(64),country VARCHAR(128),is_default TINYINT,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO agent_client(id,merchant_customer_id,reference,display_name,phone,email,active) VALUES
 (1,101,'A_001','Alice','111','private-alice@example.test',1),
 (2,202,'B-001','Bob','222','private-bob@example.test',1),
 (3,404,'D-001','Doris','444','private-doris@example.test',0),
 (4,303,'C-001','Collector client','333','private-collector@example.test',1);
INSERT INTO agent_intake(id,merchant_customer_id,client_id,intake_no,carrier_name,tracking_number,expected_card_count,status_code) VALUES
 (11,101,1,'INT-A','Postal','IN-A',2,'ready'),
 (12,202,2,'INT-B','Express','IN-B',1,'received'),
 (13,404,3,'INT-D','Postal','IN-D',1,'submitted'),
 (14,303,4,'INT-C','Postal','IN-C',1,'expected');
INSERT INTO agent_return_shipment VALUES
 (21,101,1,'RET-A','Postal','OUT-A','delivered','2026-09-13 10:00:00','2026-09-14 10:00:00'),
 (22,202,2,'RET-B','Express','OUT-B','shipped','2026-09-13 10:00:00',NULL),
 (23,404,3,'RET-D','Postal','OUT-D','shipped','2026-09-13 10:00:00',NULL),
 (24,303,4,'RET-C','Postal','OUT-C','shipped','2026-09-13 10:00:00',NULL);
INSERT INTO agent_card VALUES(31,101,11,21),(32,101,11,21),(33,202,12,22),(34,404,13,23),(35,303,14,24);
INSERT INTO merchant_order_batch(id,merchant_customer_id,batch_no,batch_name,source_name,status_code) VALUES
 (41,101,'BAT-A','Alice batch','First source','created'),
 (42,202,'BAT-B','Bob batch','Second source','created'),
 (43,404,'BAT-D','Doris batch','Historic source','completed'),
 (44,303,'BAT-C','Collector batch','Invalid source','created');
INSERT INTO grading_order VALUES(51,101,2),(52,202,1),(53,404,1),(54,303,1);
INSERT INTO merchant_order_batch_item VALUES(61,41,51),(62,42,52),(63,43,53),(64,44,54);
INSERT INTO merchant_wallet(id,customer_id,currency_code,balance) VALUES
 (71,101,'USD',123.45),(72,202,'CNY',2000),(73,404,'EUR',5.67),(74,303,'USD',10000);
INSERT INTO customer_address(id,customer_id,label,contact_name,address_line1,city,postal_code,country,is_default) VALUES
 (81,101,'Warehouse A','Alice Recipient','One Road','Shanghai','100001','CN',1),
 (82,202,'Warehouse B','Bob Recipient','Two Road','Paris','100002','FR',0),
 (83,404,'Warehouse D','Doris Recipient','Four Road','Berlin','100004','DE',1),
 (84,303,'Collector home','Collector Recipient','Three Road','London','100003','GB',1);
