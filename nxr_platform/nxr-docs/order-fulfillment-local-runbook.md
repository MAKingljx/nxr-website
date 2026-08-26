# Java 送评履约本地运行与数据保护

本文用于本地 Java 版“评级服务收费—客户寄卡—仓库入库—评级—回寄—账务—工单”闭环。所有命令默认在 `nxr_platform/` 下执行。

## 1. 数据边界

- Java 本地开发只使用 MySQL `nxr_ruoyi` 和本地 Redis，不读取或写入仓库根目录的 `Data/`。
- `Data/cards.db`、`Data/temp_cards.db` 属于 Python 系统数据；本地 Java 初始化、备份和恢复都不得指向它们。
- 不要在普通本地开发中执行 `08_nxr_python_sync.sql`、Python-to-Java 同步服务或生产部署脚本。
- `init-db.sh` 会重建本地 `nxr_ruoyi` 初始数据，只能用于明确可重建的开发库。已有本地业务数据时，必须先备份并采用增量迁移。

## 2. 首次从零启动

依赖：OpenJDK 17 或 21、Maven、Node.js、MySQL、Redis。

```bash
./nxr-scripts/dev-up.sh
./nxr-scripts/init-db.sh
./nxr-scripts/dev-backend.sh
./nxr-scripts/dev-web.sh
./nxr-scripts/dev-admin.sh
```

默认端口：用户站 `3000`、管理端 `3001`、后端 `8088`、MySQL `3306`、Redis `6379`。默认本地管理员为 `admin / admin123`，仅限开发环境。

## 3. 已有本地 Java 库升级

先建立只包含 Java MySQL 的备份；不要把目标写成生产库，也不要覆盖 `Data/`。

```bash
JAVA_BACKUP_DIR="local_backups/java_mysql/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$JAVA_BACKUP_DIR"
mysqldump --single-transaction --routines --triggers -uroot nxr_ruoyi > "$JAVA_BACKUP_DIR/nxr_ruoyi.sql"
```

确认 `01` 至 `05`、`10` 至 `12` 已应用后，再执行本期增量脚本：

```bash
mysql -uroot nxr_ruoyi < nxr-sql/ruoyi/13_nxr_order_fulfillment.sql
```

`13` 可重复执行，但执行前仍需保留备份。升级后至少检查：

```sql
SELECT COUNT(*) FROM grading_order;
SELECT price_code, unit_price, currency_code, version_no FROM grading_service_price;
SELECT option_code, price_amount, currency_code, is_active FROM return_shipping_option ORDER BY sort_order;
```

## 4. 价格配置

管理端进入“评级订单”，打开“评级与回寄价格配置”：

- 基础评级服务配置每张单价、币种和版本；
- 回寄方案配置编码、名称、适用国家、币种、价格、排序和启停；
- 价格变化只影响新订单；已创建订单继续使用服务费、回寄费和方案名称快照；
- 回寄方案变更产生独立补款或退款流水，不回写原订单金额。

## 5. 支付回调与人工兜底

本地回调默认关闭。需要联调时，通过进程环境变量提供独立测试密钥，不写入源码、日志或 Git：

```bash
export NXR_PAYMENT_CALLBACK_TOKEN="<local-test-secret>"
./nxr-scripts/dev-backend.sh
```

回调端点为 `POST /api/customer/payments/callback/{provider}`，请求头为 `X-NXR-Payment-Callback-Token`。服务端校验回调密钥、事件 ID、支付单号、金额、币种和幂等状态。

当前本地实现提供支付渠道抽象和回调闭环，不包含真实微信、支付宝或 Stripe 商户凭证。未接真实渠道时，客户可提交付款凭证，财务在管理端确认或驳回；两条路径进入同一订单状态机。

## 6. 备份恢复演练

恢复必须写入一个全新的本地库，先验证再决定是否替换开发库：

```bash
mysql -uroot -e "CREATE DATABASE nxr_ruoyi_restore_check DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
mysql -uroot nxr_ruoyi_restore_check < local_backups/java_mysql/<timestamp>/nxr_ruoyi.sql
mysqlcheck -uroot nxr_ruoyi_restore_check
```

核对表数量、订单数、金额和价格配置后，删除测试恢复库。任何生产数据库恢复、替换或迁移都必须另行取得当次明确授权。

## 7. 验证命令

```bash
cd nxr-backend-ruoyi
mvn test
mvn package -DskipTests

cd ../nxr-frontend-web
npm run build

cd ../nxr-frontend-admin-ruoyi
npm run build:prod
```

完成后检查 `git status --short`，确认没有 Python 源码、`Data/`、数据库转储、密钥或临时日志进入待提交范围。

