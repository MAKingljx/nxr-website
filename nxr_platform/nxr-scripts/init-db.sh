#!/usr/bin/env bash

# 初始化 nxr_ruoyi 数据库（若依系统表 + NXR 业务表 + 字典/角色/菜单配置）。
# 警告：会重建所有表中的初始数据，只用于本地开发环境首次初始化。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MYSQL_ROOT="${MYSQL_ROOT_CMD:-mysql -uroot}"

$MYSQL_ROOT -e "CREATE DATABASE IF NOT EXISTS nxr_ruoyi DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'nxr'@'localhost' IDENTIFIED BY 'nxr_dev_password';
CREATE USER IF NOT EXISTS 'nxr'@'127.0.0.1' IDENTIFIED BY 'nxr_dev_password';
GRANT ALL PRIVILEGES ON nxr_ruoyi.* TO 'nxr'@'localhost';
GRANT ALL PRIVILEGES ON nxr_ruoyi.* TO 'nxr'@'127.0.0.1';
FLUSH PRIVILEGES;"

$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-backend-ruoyi/sql/ry_20260417.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-backend-ruoyi/sql/quartz.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/01_nxr_business_schema.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/02_nxr_seed_data.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/03_nxr_sys_config.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/04_nxr_customer_workflow.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/05_nxr_customer_admin.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/10_nxr_product_types.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/11_nxr_admin_navigation_alignment.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/12_nxr_menu_domain_separation.sql"
$MYSQL_ROOT nxr_ruoyi < "$PLATFORM_ROOT/nxr-sql/ruoyi/13_nxr_order_fulfillment.sql"

# 与旧后台保持一致：登录不需要验证码
$MYSQL_ROOT nxr_ruoyi -e "UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled';"

echo "nxr_ruoyi 初始化完成（默认账号 admin / admin123，请尽快修改密码）"
