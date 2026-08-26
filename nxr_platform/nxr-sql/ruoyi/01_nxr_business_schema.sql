-- ----------------------------------------------------------------------------
-- NXR 业务表结构（合并自原 nxr-backend-java Flyway V1/V3/V4/V5/V6 的最终形态）
-- 与若依 sys_* 表共库使用。
-- 差异说明：
--   1. 原 admin_user 表被若依 sys_user 取代，本脚本不再创建；
--      entry_by_user_id / approved_by_user_id / created_by_user_id 改为逻辑引用
--      sys_user.user_id（无外键约束）。
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS grading_submission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cert_id VARCHAR(32) NOT NULL,
    card_name VARCHAR(255) NOT NULL,
    year_label VARCHAR(16),
    brand_name VARCHAR(64) NOT NULL,
    player_name VARCHAR(128),
    variety_name VARCHAR(255),
    set_name VARCHAR(255) NOT NULL,
    card_number VARCHAR(64) NOT NULL,
    language_code VARCHAR(16) NOT NULL DEFAULT 'EN',
    population_value INT NOT NULL DEFAULT 1,
    status_code VARCHAR(32) NOT NULL DEFAULT 'pending',
    grading_phase_code VARCHAR(32) NOT NULL DEFAULT 'human_review',
    product_type_code VARCHAR(32) NOT NULL DEFAULT 'graded_card',
    vintage_classification_code VARCHAR(64) NULL,
    merch_description TEXT NULL,
    card_category_code VARCHAR(32) NOT NULL DEFAULT 'trading_card',
    movie_name VARCHAR(255) NULL,
    release_year VARCHAR(16) NULL,
    production_company VARCHAR(128) NULL,
    film_type VARCHAR(128) NULL,
    sports_type VARCHAR(64) NULL,
    group_name VARCHAR(128) NULL,
    approval_sequence BIGINT NULL,
    entry_notes TEXT,
    entry_by_user_id BIGINT NULL,
    approved_by_user_id BIGINT NULL,
    approved_at TIMESTAMP NULL,
    published_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_grading_submission_cert_id (cert_id),
    KEY idx_grading_submission_status_created (status_code, created_at),
    KEY idx_grading_submission_identity (brand_name, set_name, card_number, language_code),
    KEY idx_grading_submission_product_status (product_type_code, status_code, created_at),
    KEY idx_grading_submission_vintage_classification (product_type_code, vintage_classification_code),
    KEY idx_grading_submission_category_status (card_category_code, status_code, created_at),
    KEY idx_grading_submission_trading_pop (card_category_code, card_name, set_name, card_number, language_code),
    KEY idx_grading_submission_movie_pop (card_category_code, movie_name, release_year, production_company, film_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS grading_score (
    submission_id BIGINT PRIMARY KEY,
    centering_score DECIMAL(4,1) NOT NULL,
    edges_score DECIMAL(4,1) NOT NULL,
    corners_score DECIMAL(4,1) NOT NULL,
    surface_score DECIMAL(4,1) NOT NULL,
    final_grade_value DECIMAL(4,1) NOT NULL,
    final_grade_label VARCHAR(64) NOT NULL,
    ai_grade_value DECIMAL(4,1) NULL,
    ai_confidence_value DECIMAL(5,2) NULL,
    decision_method_code VARCHAR(32) NOT NULL DEFAULT 'human_only',
    decision_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_grading_score_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS submission_media (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    cert_id VARCHAR(32) NOT NULL,
    media_side_code VARCHAR(16) NOT NULL,
    media_stage_code VARCHAR(16) NOT NULL DEFAULT 'queue',
    storage_provider_code VARCHAR(32) NOT NULL DEFAULT 'local',
    storage_bucket VARCHAR(128) NULL,
    storage_key VARCHAR(255) NOT NULL,
    storage_object_version VARCHAR(128) NULL,
    public_url VARCHAR(255),
    width_px INT NULL,
    height_px INT NULL,
    sort_order INT NOT NULL DEFAULT 1,
    source_media_id BIGINT NULL,
    is_active TINYINT NOT NULL DEFAULT 1,
    original_filename VARCHAR(255) NULL,
    mime_type VARCHAR(128) NULL,
    file_size_bytes BIGINT NULL,
    checksum_sha256 CHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_submission_media_stage_side (submission_id, media_stage_code, media_side_code, sort_order),
    UNIQUE KEY uk_submission_media_provider_key (storage_provider_code, storage_bucket, storage_key),
    KEY idx_submission_media_cert_stage (cert_id, media_stage_code),
    KEY idx_submission_media_stage_active (submission_id, media_stage_code, is_active),
    KEY idx_submission_media_stage_side_active (submission_id, media_stage_code, media_side_code, is_active, sort_order),
    CONSTRAINT fk_submission_media_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE,
    CONSTRAINT fk_submission_media_source_media FOREIGN KEY (source_media_id) REFERENCES submission_media(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS published_certificate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    cert_id VARCHAR(32) NOT NULL,
    verification_slug VARCHAR(64) NOT NULL,
    qr_url VARCHAR(255),
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_front_media_id BIGINT NULL,
    published_back_media_id BIGINT NULL,
    published_media_snapshot_json LONGTEXT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_published_certificate_cert_id (cert_id),
    UNIQUE KEY uk_published_certificate_slug (verification_slug),
    UNIQUE KEY uk_published_certificate_submission_id (submission_id),
    KEY idx_published_certificate_published_at (published_at),
    CONSTRAINT fk_published_certificate_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id),
    CONSTRAINT fk_published_certificate_front_media FOREIGN KEY (published_front_media_id) REFERENCES submission_media(id) ON DELETE SET NULL,
    CONSTRAINT fk_published_certificate_back_media FOREIGN KEY (published_back_media_id) REFERENCES submission_media(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS waitlist_email (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    source_code VARCHAR(32) NOT NULL DEFAULT 'web',
    status_code VARCHAR(32) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_waitlist_email_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS brand_settings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    aliases TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_brand_settings_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS export_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    filename VARCHAR(255) NOT NULL,
    filter_label VARCHAR(255) NOT NULL,
    grade_filter VARCHAR(64) NULL,
    cert_ids TEXT,
    record_count INT NOT NULL DEFAULT 0,
    file_size_bytes BIGINT NOT NULL DEFAULT 0,
    storage_path VARCHAR(512) NOT NULL,
    created_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_export_job_filename (filename),
    KEY idx_export_job_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_character_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cert_id VARCHAR(64) NOT NULL,
    brand_name VARCHAR(128) NOT NULL,
    character_name VARCHAR(255) NOT NULL,
    language_code VARCHAR(16) NOT NULL DEFAULT 'en',
    content_html MEDIUMTEXT NOT NULL,
    provider_code VARCHAR(32) NOT NULL DEFAULT 'local',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_character_cache_cert_language (cert_id, language_code),
    KEY idx_ai_character_cache_character (character_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
