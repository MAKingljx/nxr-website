CREATE TABLE admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    email VARCHAR(255),
    role_code VARCHAR(32) NOT NULL DEFAULT 'admin',
    is_active TINYINT NOT NULL DEFAULT 1,
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admin_user_username UNIQUE (username)
);

CREATE TABLE grading_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    entry_notes CLOB,
    entry_by_user_id BIGINT NULL,
    approved_by_user_id BIGINT NULL,
    approved_at TIMESTAMP NULL,
    published_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_grading_submission_cert_id UNIQUE (cert_id),
    CONSTRAINT fk_grading_submission_entry_user FOREIGN KEY (entry_by_user_id) REFERENCES admin_user(id),
    CONSTRAINT fk_grading_submission_approved_user FOREIGN KEY (approved_by_user_id) REFERENCES admin_user(id)
);

CREATE TABLE grading_score (
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
    decision_notes CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_grading_score_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE
);

CREATE TABLE submission_media (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    cert_id VARCHAR(32) NOT NULL,
    media_side_code VARCHAR(16) NOT NULL,
    media_stage_code VARCHAR(16) NOT NULL DEFAULT 'queue',
    storage_provider_code VARCHAR(32) NOT NULL DEFAULT 'local',
    storage_key VARCHAR(255) NOT NULL,
    public_url VARCHAR(255),
    width_px INT NULL,
    height_px INT NULL,
    sort_order INT NOT NULL DEFAULT 1,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_submission_media_stage_side UNIQUE (submission_id, media_stage_code, media_side_code, sort_order),
    CONSTRAINT fk_submission_media_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE
);

CREATE TABLE published_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    cert_id VARCHAR(32) NOT NULL,
    verification_slug VARCHAR(64) NOT NULL,
    qr_url VARCHAR(255),
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_published_certificate_cert_id UNIQUE (cert_id),
    CONSTRAINT uk_published_certificate_slug UNIQUE (verification_slug),
    CONSTRAINT fk_published_certificate_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id)
);

CREATE TABLE waitlist_email (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    source_code VARCHAR(32) NOT NULL DEFAULT 'web',
    status_code VARCHAR(32) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_waitlist_email_email UNIQUE (email)
);
