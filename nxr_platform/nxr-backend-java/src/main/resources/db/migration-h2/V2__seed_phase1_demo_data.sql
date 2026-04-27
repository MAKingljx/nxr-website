INSERT INTO admin_user (
    id,
    username,
    password_hash,
    display_name,
    email,
    role_code,
    is_active
) VALUES (
    1,
    'nxr_admin',
    '$2y$10$pZSqv4p4ds9gddnFHS.Ajem3W7mPWEF99qPFN99PhwccqBbCSeozW',
    'NXR Platform Admin',
    'admin@nxrgrading.com',
    'superadmin',
    1
);

INSERT INTO grading_submission (
    id,
    cert_id,
    card_name,
    year_label,
    brand_name,
    player_name,
    variety_name,
    set_name,
    card_number,
    language_code,
    population_value,
    status_code,
    grading_phase_code,
    entry_notes,
    entry_by_user_id,
    approved_by_user_id,
    approved_at,
    published_at,
    created_at,
    updated_at
) VALUES
(1001, 'VRA003', 'Umbreon VMAX Alternate Art', '2021', 'Pokemon', 'Umbreon', 'Alternate Art Secret', 'Evolving Skies', '215/203', 'EN', 3, 'published', 'ai_plus_human', 'Legacy published certificate migrated into the new platform model.', 1, 1, '2026-04-20 10:00:00', '2026-04-20 11:00:00', '2026-04-20 09:15:00', '2026-04-20 11:00:00'),
(1002, 'NXR2026032401', 'Pikachu Illustration Rare', '2024', 'Pokemon', 'Pikachu', 'Illustration Rare', 'Scarlet & Violet', '173/165', 'EN', 8, 'published', 'ai_plus_human', 'Public certificate used for verify and card-detail regression coverage.', 1, 1, '2026-04-22 14:10:00', '2026-04-22 15:00:00', '2026-04-22 13:20:00', '2026-04-22 15:00:00'),
(1003, '5703018202', 'Gengar VMAX', '2022', 'Pokemon', 'Gengar', 'Alternate Art', 'Fusion Strike', '271/264', 'JP', 2, 'published', 'ai_plus_human', 'Includes front and back media samples for the new Vue card layout.', 1, 1, '2026-04-24 18:25:00', '2026-04-24 18:50:00', '2026-04-24 17:55:00', '2026-04-24 18:50:00'),
(1004, 'NXR2026042601', 'Charizard ex', '2024', 'Pokemon', 'Charizard', 'Special Illustration Rare', 'Paldean Fates', '234/091', 'EN', 1, 'pending', 'human_review', 'Fresh intake waiting for senior review.', 1, NULL, NULL, NULL, '2026-04-26 09:00:00', '2026-04-26 09:00:00'),
(1005, 'NXR2026042602', 'Lugia V', '2023', 'Pokemon', 'Lugia', 'Alternate Art', 'Silver Tempest', '186/195', 'EN', 1, 'approved', 'human_review', 'Approved and ready for media publish.', 1, 1, '2026-04-26 16:00:00', NULL, '2026-04-26 12:00:00', '2026-04-26 16:00:00');

INSERT INTO grading_score (
    submission_id,
    centering_score,
    edges_score,
    corners_score,
    surface_score,
    final_grade_value,
    final_grade_label,
    ai_grade_value,
    ai_confidence_value,
    decision_method_code,
    decision_notes
) VALUES
(1001, 9.5, 10.0, 9.5, 9.5, 9.6, 'Gem Mint 9.5', 9.6, 98.20, 'ai_plus_human', 'AI and grader both aligned on premium surface and edges.'),
(1002, 9.0, 9.5, 9.5, 9.0, 9.3, 'Mint 9', 9.2, 96.40, 'ai_plus_human', 'Slight centering drift keeps this out of Gem Mint.'),
(1003, 9.5, 9.0, 9.0, 9.5, 9.3, 'Mint 9', 9.4, 95.60, 'ai_plus_human', 'Very strong front presentation with minor edge wear.'),
(1004, 8.5, 9.0, 9.0, 8.5, 8.8, 'Near Mint-Mint+ 8.5', 8.7, 91.30, 'human_only', 'Needs second pass before approval.'),
(1005, 9.5, 9.5, 9.5, 9.0, 9.4, 'Mint 9', 9.3, 94.20, 'human_only', 'Approved and awaiting publish media.');

INSERT INTO submission_media (
    submission_id,
    cert_id,
    media_side_code,
    media_stage_code,
    storage_provider_code,
    storage_key,
    public_url,
    sort_order,
    is_active
) VALUES
(1001, 'VRA003', 'front', 'published', 'legacy-static', 'vra003_front_compressed.webp', 'https://nxrgrading.com/static/vra003_front_compressed.webp', 1, 1),
(1001, 'VRA003', 'back', 'published', 'legacy-static', 'vra003_back_compressed.webp', 'https://nxrgrading.com/static/vra003_back_compressed.webp', 1, 1),
(1002, 'NXR2026032401', 'front', 'published', 'legacy-static', 'nxr2026032401_front_compressed.webp', 'https://nxrgrading.com/static/nxr2026032401_front_compressed.webp', 1, 1),
(1002, 'NXR2026032401', 'back', 'published', 'legacy-static', 'nxr2026032401_back_compressed.webp', 'https://nxrgrading.com/static/nxr2026032401_back_compressed.webp', 1, 1),
(1003, '5703018202', 'front', 'published', 'legacy-static', '5703018202_front.jpg', 'https://nxrgrading.com/static/5703018202_front.jpg', 1, 1),
(1003, '5703018202', 'back', 'published', 'legacy-static', '5703018202_back.jpg', 'https://nxrgrading.com/static/5703018202_back.jpg', 1, 1);

INSERT INTO published_certificate (
    submission_id,
    cert_id,
    verification_slug,
    qr_url,
    published_at
) VALUES
(1001, 'VRA003', 'vra003', 'https://nxrgrading.com/card/VRA003', '2026-04-20 11:00:00'),
(1002, 'NXR2026032401', 'nxr2026032401', 'https://nxrgrading.com/card/NXR2026032401', '2026-04-22 15:00:00'),
(1003, '5703018202', '5703018202', 'https://nxrgrading.com/card/5703018202', '2026-04-24 18:50:00');

INSERT INTO waitlist_email (email, source_code, status_code) VALUES
('collector1@demo.nxr', 'web', 'confirmed'),
('collector2@demo.nxr', 'web', 'confirmed'),
('collector3@demo.nxr', 'campaign', 'pending'),
('collector4@demo.nxr', 'campaign', 'pending');
