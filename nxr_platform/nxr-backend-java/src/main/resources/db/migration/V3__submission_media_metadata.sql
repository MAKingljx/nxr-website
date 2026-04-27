ALTER TABLE submission_media
    ADD COLUMN original_filename VARCHAR(255) NULL;

ALTER TABLE submission_media
    ADD COLUMN mime_type VARCHAR(128) NULL;

ALTER TABLE submission_media
    ADD COLUMN file_size_bytes BIGINT NULL;

ALTER TABLE submission_media
    ADD COLUMN checksum_sha256 CHAR(64) NULL;

CREATE INDEX idx_submission_media_stage_active
    ON submission_media (submission_id, media_stage_code, is_active);
