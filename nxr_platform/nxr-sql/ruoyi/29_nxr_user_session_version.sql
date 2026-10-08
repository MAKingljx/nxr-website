-- Required before starting the Java authentication runtime.
-- No rows means credential version zero; existing sessions remain valid.
CREATE TABLE IF NOT EXISTS sys_user_session_version (
    user_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_session_version_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
