#!/usr/bin/env bash

# Initializes only a brand-new nxr_java_stage database. The guard refuses to
# run if any table already exists, so this cannot be used as an upgrade script.

set -euo pipefail

DB_NAME="${NXR_STAGE_DB_NAME:-nxr_java_stage}"
MYSQL_ROOT_CMD="${MYSQL_ROOT_CMD:-mysql -uroot}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ "$DB_NAME" != "nxr_java_stage" ]]; then
  echo "Refusing database name: $DB_NAME" >&2
  exit 2
fi

schema_count="$($MYSQL_ROOT_CMD -Nse "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB_NAME'")"
if [[ "$schema_count" != "1" ]]; then
  echo "Database $DB_NAME must already exist before initialization" >&2
  exit 3
fi

table_count="$($MYSQL_ROOT_CMD -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'")"
if [[ "$table_count" != "0" ]]; then
  echo "Refusing to initialize non-empty database $DB_NAME ($table_count tables)" >&2
  exit 4
fi

scripts=(
  "$PLATFORM_ROOT/nxr-backend-ruoyi/sql/ry_20260417.sql"
  "$PLATFORM_ROOT/nxr-backend-ruoyi/sql/quartz.sql"
  "$PLATFORM_ROOT/nxr-sql/ruoyi/01_nxr_business_schema.sql"
  "$PLATFORM_ROOT/nxr-sql/ruoyi/02_nxr_seed_data.sql"
  "$PLATFORM_ROOT/nxr-sql/ruoyi/03_nxr_sys_config.sql"
  "$PLATFORM_ROOT/nxr-sql/ruoyi/04_nxr_customer_workflow.sql"
  "$PLATFORM_ROOT/nxr-sql/ruoyi/05_nxr_customer_admin.sql"
)

for script in "${scripts[@]}"; do
  $MYSQL_ROOT_CMD "$DB_NAME" < "$script"
done

# Default seed credentials are never left enabled, even while the service is
# still bound to localhost. Activation is a separate, explicit operation.
$MYSQL_ROOT_CMD "$DB_NAME" <<'SQL'
START TRANSACTION;
UPDATE sys_config
SET config_value = 'false'
WHERE config_key = 'sys.account.captchaEnabled';
UPDATE sys_user
SET status = '1'
WHERE user_name IN ('admin', 'ry');
COMMIT;
SQL

non_innodb_count="$($MYSQL_ROOT_CMD -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_type='BASE TABLE' AND engine <> 'InnoDB'")"
if [[ "$non_innodb_count" != "0" ]]; then
  echo "Initialization found $non_innodb_count non-InnoDB tables" >&2
  exit 5
fi

echo "Initialized $DB_NAME with seed staff accounts disabled."
