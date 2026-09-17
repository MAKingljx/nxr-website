-- Identity projections use the same custody links as the full agent schema; no private contact columns are needed here.
CREATE TABLE agent_client(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT NOT NULL,reference VARCHAR(64),display_name VARCHAR(128));
CREATE TABLE agent_intake(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT NOT NULL,client_id BIGINT NOT NULL,order_id BIGINT UNIQUE);
CREATE TABLE agent_card(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT NOT NULL,intake_id BIGINT NOT NULL,
 inventory_code VARCHAR(64) UNIQUE,order_item_id BIGINT UNIQUE);
CREATE TABLE merchant_company_profile(customer_id BIGINT PRIMARY KEY,company_name VARCHAR(191));
CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1));
CREATE TABLE agent_operator_binding(sys_user_id BIGINT PRIMARY KEY,merchant_customer_id BIGINT NOT NULL,active BOOLEAN);
