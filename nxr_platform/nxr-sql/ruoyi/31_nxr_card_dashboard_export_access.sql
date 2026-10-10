-- Card-operator access to the card dashboard and Excel export workspace.
-- This migration changes only menu metadata and role-menu grants. It does not
-- read or modify card, order, customer, payment, media or export-job records.
-- Rerunnable: exact stable menu identities are checked before bounded grants.

CREATE TEMPORARY TABLE nxr_card_workspace_access_guard (
    valid TINYINT NOT NULL CHECK (valid = 1)
);

INSERT INTO nxr_card_workspace_access_guard
SELECT IF(
    (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2000 AND path = 'nxr/cards' AND menu_type = 'M' AND status = '0') = 1
    AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2003 AND parent_id = 2000 AND component = 'nxr/exports/index' AND perms = 'nxr:export:list' AND status = '0') = 1
    AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2031 AND parent_id = 2003 AND perms = 'nxr:export:generate' AND status = '0') = 1
    AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2032 AND parent_id = 2003 AND perms = 'nxr:export:remove' AND status = '0') = 1
    AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2081 AND parent_id = 2000 AND perms = 'nxr:dashboard:view' AND status = '0') = 1
    AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2084 AND parent_id = 2000 AND component = 'nxr/entries/index' AND status = '0') = 1
    AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2160 AND parent_id = 2000 AND perms = 'nxr:card:global' AND status = '0') = 1
    AND NOT EXISTS (
        SELECT 1 FROM sys_role
        WHERE role_key IN ('nxr_card_manager', 'nxr_card_super_manager')
          AND (status <> '0' OR del_flag <> '0' OR data_scope <> '1')
    ),
    1,
    0
);

DROP TEMPORARY TABLE nxr_card_workspace_access_guard;

START TRANSACTION;

-- The approved shortcut mirrors the legacy dashboard total: approved records
-- include entries that have already progressed to publication.
UPDATE sys_menu
SET query = '{"status":"approved_history"}',
    remark = '已批准及已发布录入快捷视图',
    update_by = 'admin',
    update_time = CURRENT_TIMESTAMP
WHERE menu_id = 2084;

-- Card operators can view card-only dashboard data, generate exports, and
-- download their shared export history. Deletion remains a supervisor action.
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.menu_id IN (2000, 2003, 2031, 2081, 2160) AND m.status = '0'
WHERE r.role_key IN ('nxr_card_manager', 'nxr_card_super_manager')
  AND r.status = '0'
  AND r.del_flag = '0';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, 2032
FROM sys_role r
WHERE r.role_key = 'nxr_card_super_manager'
  AND r.status = '0'
  AND r.del_flag = '0';

COMMIT;
