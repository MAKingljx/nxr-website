ALTER TABLE agent_intake ADD COLUMN intake_no VARCHAR(48);
ALTER TABLE grading_order ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP;
ALTER TABLE grading_order ADD COLUMN IF NOT EXISTS accepted_terms_version VARCHAR(64);
ALTER TABLE grading_order ADD COLUMN IF NOT EXISTS approved_terms_version VARCHAR(64);
ALTER TABLE grading_order ADD COLUMN business_line_id BIGINT DEFAULT 1;
ALTER TABLE grading_order ADD COLUMN work_center_id BIGINT DEFAULT 1;
CREATE TABLE submission_media(id BIGINT PRIMARY KEY AUTO_INCREMENT,submission_id BIGINT,media_side_code VARCHAR(16),storage_key VARCHAR(255),checksum_sha256 VARCHAR(64),public_url VARCHAR(255),is_active INT DEFAULT 1,media_stage_code VARCHAR(16) DEFAULT 'staged');
CREATE TABLE partner_return_card_check(id BIGINT PRIMARY KEY AUTO_INCREMENT,order_item_id BIGINT,content_fingerprint CHAR(64),checked_by_user_id BIGINT,note VARCHAR(2000),checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE partner_return_parcel(id BIGINT PRIMARY KEY AUTO_INCREMENT,parcel_no VARCHAR(48) UNIQUE,merchant_customer_id BIGINT,carrier_name VARCHAR(128),tracking_number VARCHAR(255),status_code VARCHAR(16) DEFAULT 'shipped',return_address TEXT,address_fingerprint CHAR(64),request_key VARCHAR(128),request_hash CHAR(64),note VARCHAR(2000),created_by_user_id BIGINT,shipped_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,delivered_at TIMESTAMP,delivered_by_user_id BIGINT,UNIQUE(merchant_customer_id,request_key),UNIQUE(carrier_name,tracking_number));
CREATE TABLE partner_return_item(id BIGINT PRIMARY KEY AUTO_INCREMENT,parcel_id BIGINT NOT NULL,order_item_id BIGINT NOT NULL UNIQUE,check_id BIGINT,cert_id VARCHAR(32),content_fingerprint CHAR(64),FOREIGN KEY(parcel_id) REFERENCES partner_return_parcel(id),FOREIGN KEY(order_item_id) REFERENCES grading_order_item(id),FOREIGN KEY(check_id) REFERENCES partner_return_card_check(id));
UPDATE grading_order SET status_code='grading',admission_status_code='approved',terms_accepted_at=CURRENT_TIMESTAMP,accepted_terms_version='v1',approved_terms_version='v1',workbench_required=0;
INSERT INTO agent_intake(id,merchant_customer_id,client_id,order_id) VALUES(1,1,1,10),(2,1,2,20);
INSERT INTO agent_card(id,merchant_customer_id,intake_id,inventory_code,order_item_id) VALUES(1,1,1,'NXR-C1',1001),(2,1,1,'NXR-C2',1002),(3,1,2,'NXR-C3',2001);
INSERT INTO merchant_order_batch(id,batch_no,merchant_customer_id,batch_name,status_code,total_rows) VALUES(1,'BATCH-A',1,'First parcel','received',1),(2,'BATCH-B',1,'Second parcel','received',1);
INSERT INTO merchant_order_batch_item(batch_id,order_id,row_no,client_reference) VALUES(1,10,1,'O1'),(2,20,1,'O2');
INSERT INTO payment_record(order_id,direction_code,payment_type_code,provider_code,status_code,amount,currency_code) VALUES(10,'receivable','grading_fee','manual_transfer','confirmed',30,'USD'),(20,'receivable','grading_fee','manual_transfer','confirmed',20,'USD');
INSERT INTO submission_media(submission_id,media_side_code,storage_key,checksum_sha256,public_url) VALUES(101,'front','101-f','abc','/images/101-f.webp'),(101,'back','101-b','abc','/images/101-b.webp'),(102,'front','102-f','abc','/images/102-f.webp'),(102,'back','102-b','abc','/images/102-b.webp'),(201,'front','201-f','abc','/images/201-f.webp'),(201,'back','201-b','abc','/images/201-b.webp');

UPDATE agent_intake SET intake_no=CONCAT('NXR-',id);
