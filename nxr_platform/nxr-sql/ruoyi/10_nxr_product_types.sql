-- ----------------------------------------------------------------------------
-- NXR 产品类型兼容增量脚本。
--
-- 适用于已经完成 01/03 初始化的本地 Java/RuoYi MySQL 库；脚本只补列、索引
-- 和字典元数据，不创建评分占位行。MySQL DDL 会隐式提交，因此应在执行前按环境
-- 的数据库规范单独备份；本文件本身保持可重复执行。
-- ----------------------------------------------------------------------------

SET @nxr_schema = DATABASE();

SET @nxr_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @nxr_schema
       AND table_name = 'grading_submission'
       AND column_name = 'product_type_code') = 0,
    'ALTER TABLE grading_submission ADD COLUMN product_type_code VARCHAR(32) NOT NULL DEFAULT ''graded_card'' AFTER grading_phase_code',
    'SELECT 1'
);
PREPARE nxr_stmt FROM @nxr_sql;
EXECUTE nxr_stmt;
DEALLOCATE PREPARE nxr_stmt;

SET @nxr_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @nxr_schema
       AND table_name = 'grading_submission'
       AND column_name = 'vintage_classification_code') = 0,
    'ALTER TABLE grading_submission ADD COLUMN vintage_classification_code VARCHAR(64) NULL AFTER product_type_code',
    'SELECT 1'
);
PREPARE nxr_stmt FROM @nxr_sql;
EXECUTE nxr_stmt;
DEALLOCATE PREPARE nxr_stmt;

SET @nxr_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @nxr_schema
       AND table_name = 'grading_submission'
       AND column_name = 'merch_description') = 0,
    'ALTER TABLE grading_submission ADD COLUMN merch_description TEXT NULL AFTER vintage_classification_code',
    'SELECT 1'
);
PREPARE nxr_stmt FROM @nxr_sql;
EXECUTE nxr_stmt;
DEALLOCATE PREPARE nxr_stmt;

SET @nxr_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @nxr_schema
       AND table_name = 'grading_submission'
       AND index_name = 'idx_grading_submission_product_status') = 0,
    'CREATE INDEX idx_grading_submission_product_status ON grading_submission (product_type_code, status_code, created_at)',
    'SELECT 1'
);
PREPARE nxr_stmt FROM @nxr_sql;
EXECUTE nxr_stmt;
DEALLOCATE PREPARE nxr_stmt;

SET @nxr_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @nxr_schema
       AND table_name = 'grading_submission'
       AND index_name = 'idx_grading_submission_vintage_classification') = 0,
    'CREATE INDEX idx_grading_submission_vintage_classification ON grading_submission (product_type_code, vintage_classification_code)',
    'SELECT 1'
);
PREPARE nxr_stmt FROM @nxr_sql;
EXECUTE nxr_stmt;
DEALLOCATE PREPARE nxr_stmt;

START TRANSACTION;

UPDATE grading_submission
SET product_type_code = CASE
    WHEN LOWER(TRIM(COALESCE(product_type_code, ''))) IN
         ('merch', 'merch product', 'merch_product', 'merch-product',
          'label', 'label product', 'label_product', 'label-product')
        THEN 'merch_product'
    WHEN LOWER(TRIM(COALESCE(product_type_code, ''))) IN
         ('vintage', 'vintage card', 'vintage_card', 'vintage-card',
          'vintage product', 'vintage_product', 'vintage-product')
        THEN 'vintage_product'
    ELSE 'graded_card'
END;

INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
SELECT '产品类型', 'nxr_product_type', '0', 'admin', sysdate(), '录入流程产品类型；编码由后端固定校验'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'nxr_product_type');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 10, 'Graded Card', 'graded_card', 'nxr_product_type', '', '', 'Y', '0', 'admin', sysdate(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'nxr_product_type' AND dict_value = 'graded_card');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20, 'Merch Product', 'merch_product', 'nxr_product_type', '', '', 'N', '0', 'admin', sysdate(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'nxr_product_type' AND dict_value = 'merch_product');

UPDATE sys_dict_data
SET dict_label = 'Merch Product', status = '0'
WHERE dict_type = 'nxr_product_type' AND dict_value = 'merch_product';

UPDATE sys_dict_data
SET dict_label = 'Label Product (legacy)', status = '1',
    remark = '历史兼容值；新录入统一使用 merch_product'
WHERE dict_type = 'nxr_product_type' AND dict_value = 'label_product';

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 30, 'Vintage Card', 'vintage_product', 'nxr_product_type', '', '', 'N', '0', 'admin', sysdate(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'nxr_product_type' AND dict_value = 'vintage_product');

INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
SELECT '老卡分类', 'nxr_vintage_classification', '0', 'admin', sysdate(), 'Vintage Card 的四分类'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'nxr_vintage_classification');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 10, 'Pristine', 'Pristine', 'nxr_vintage_classification', '', '', 'Y', '0', 'admin', sysdate(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'nxr_vintage_classification' AND dict_value = 'Pristine');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20, 'Nova', 'Nova', 'nxr_vintage_classification', '', '', 'N', '0', 'admin', sysdate(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'nxr_vintage_classification' AND dict_value = 'Nova');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 30, 'Legacy', 'Legacy', 'nxr_vintage_classification', '', '', 'N', '0', 'admin', sysdate(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'nxr_vintage_classification' AND dict_value = 'Legacy');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 40, 'Helix', 'Helix', 'nxr_vintage_classification', '', '', 'N', '0', 'admin', sysdate(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type = 'nxr_vintage_classification' AND dict_value = 'Helix');

COMMIT;
