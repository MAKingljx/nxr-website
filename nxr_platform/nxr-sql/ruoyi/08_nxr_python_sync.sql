-- Metadata used by the read-only Python SQLite -> Java MySQL synchronizer.
-- The Python applications never read or write these tables.

CREATE TABLE IF NOT EXISTS nxr_python_sync_submission (
    cert_id VARCHAR(32) NOT NULL,
    submission_id BIGINT NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    source_updated_at DATETIME NULL,
    first_synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (cert_id),
    UNIQUE KEY uk_nxr_python_sync_submission_id (submission_id),
    CONSTRAINT fk_nxr_python_sync_submission
        FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nxr_python_sync_state (
    stream_name VARCHAR(64) NOT NULL,
    cursor_json JSON NOT NULL,
    last_full_sync_at TIMESTAMP NULL,
    last_success_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stream_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
