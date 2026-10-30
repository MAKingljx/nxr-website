-- Keep B-side legacy status fields aligned with the Java order and batch status
-- fields. Mixed collations make CASE/COALESCE status filters fail at runtime.
ALTER TABLE agent_intake
    MODIFY COLUMN status_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'expected';

ALTER TABLE agent_card
    MODIFY COLUMN status_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'expected';

ALTER TABLE agent_return_shipment
    MODIFY COLUMN status_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'shipped';
