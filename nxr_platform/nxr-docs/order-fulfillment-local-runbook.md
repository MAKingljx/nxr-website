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
JAVA_BACKUP_DIR="../local_backups/java_mysql/$(date +%Y%m%d_%H%M%S)"
umask 077
mkdir -p "$JAVA_BACKUP_DIR"
mysqldump --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF -uroot nxr_ruoyi > "$JAVA_BACKUP_DIR/nxr_ruoyi.sql"
```

确认 `01` 至 `05`、`10` 至 `12` 已应用后，从尚未应用的最早脚本开始，按顺序执行 `13` 至 `18`：

```bash
mysql -uroot nxr_ruoyi < nxr-sql/ruoyi/13_nxr_order_fulfillment.sql
mysql -uroot nxr_ruoyi < nxr-sql/ruoyi/14_nxr_two_module_navigation.sql
mysql -uroot nxr_ruoyi < nxr-sql/ruoyi/15_nxr_python_feature_parity.sql
mysql -uroot nxr_ruoyi < nxr-sql/ruoyi/16_nxr_customer_wallet.sql
mysql -uroot nxr_ruoyi < nxr-sql/ruoyi/17_nxr_payment_channels.sql
mysql -uroot nxr_ruoyi < nxr-sql/ruoyi/18_nxr_customer_notifications.sql
```

`13` 补齐订单履约结构，`14` 对齐当前两模块后台导航。`15` 为旧库补齐 Python 功能对等所需结构，包括录入人标签、两位小数最终等级、AI 分项评分、上传状态表和自动匹配读模型，并规范化已知等级标签。`16` 增加企业资料、分币种余额与不可重复的账务流水；`17` 增加支付渠道、支付尝试与财务配置权限；`18` 增加验证令牌与邮件 outbox。脚本均按可重复执行设计，但其中包含结构和数据变更，每次执行前仍必须保留并验证本地 Java MySQL 备份；不要对生产库或仓库根目录的 `Data/` 执行。

升级后先核对结构，不读取业务记录：

```sql
SELECT table_name, column_name, data_type, numeric_precision, numeric_scale
FROM information_schema.columns
WHERE table_schema = 'nxr_ruoyi'
  AND (
    (table_name = 'grading_submission' AND column_name = 'entry_by_label')
    OR (table_name = 'grading_score' AND column_name IN (
      'final_grade_value', 'ai_centering_score', 'ai_edges_score',
      'ai_corners_score', 'ai_surface_score'
    ))
  )
ORDER BY table_name, column_name;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'nxr_ruoyi'
  AND table_name IN ('submission_upload_state', 'nxr_python_match_projection')
ORDER BY table_name;
```

结果应包含上述 6 个列和 2 张表，且 `grading_score.final_grade_value` 的 `numeric_scale` 为 `2`。再按本次开发范围检查订单配置：

```sql
SELECT COUNT(*) FROM grading_order;
SELECT price_code, unit_price, currency_code, version_no FROM grading_service_price;
SELECT option_code, price_amount, currency_code, is_active FROM return_shipping_option ORDER BY sort_order;
```

## 4. 价格配置

管理端进入“评级订单”，打开“评级与回寄价格配置”：

- 基础评级服务按币种分别配置每张单价和版本；可选 USD、CNY、EUR、GBP、HKD、JPY、CAD、AUD、SGD，JPY 金额不允许小数；
- 用户只能选择已配置的评级币种，并且回寄方案须使用相同币种；不做隐式汇率换算；
- 回寄方案配置编码、名称、适用国家、币种、价格、排序和启停；
- 价格变化只影响新订单；已创建订单继续使用服务费、回寄费和方案名称快照；
- 回寄方案变更产生独立补款或退款流水，不回写原订单金额。

## 5. 支付与企业余额

后台“系统设置 → 支付渠道”支持微信 Native、支付宝当面付、PayPal Orders v2。微信与支付宝按 CNY 收款；PayPal 按已获批商户实际支持的币种配置。所有渠道初始关闭，不包含商户凭据，也不会因迁移而开通收款。国内中国主体需按对应渠道审核，海外币种收款能力以商户开通结果为准。

付款端点为 `POST /api/customer/orders/{orderNo}/checkout`，需要客户令牌并只使用服务端订单金额。真实渠道回调为 `/api/payments/webhooks/{provider}`；由提供商验签后核对商户、金额、币种及交易标识，回调与钱包结算共享订单互斥和幂等约束。旧 `/api/customer/payments/callback/{provider}` 仅留兼容测试，未设置 `NXR_PAYMENT_CALLBACK_TOKEN` 时关闭，不是提供商的真实验签入口。

API 配置中的商户凭据以 AES-GCM 加密存储，接口仅回传掩码，操作日志不记录请求或响应正文。主密钥由 `NXR_PAYMENT_MASTER_KEY`（32 字节 Base64）或 `NXR_PAYMENT_MASTER_KEY_FILE` 提供；本地启动脚本可读取 `~/.config/nxr-local/payment-master.key`（0600），该文件必须在仓库和发行包之外单独备份。不要更换既有密钥，否则已加密配置无法解密。

企业账号由管理员在“客户管理”设置类型。在客户详情“公司与预充值”页填写公司资料、核验充值和查看流水。财务需要 `nxr:customer:finance` 权限；充值确认必须填写真实收款流水号和说明，同一渠道流水不能重复入账。用户在 `/account/company` 管理公司资料、分币种余额和充值申请，在订单页使用同币种余额支付。待核款充值不增加余额。余额不足或币种不符时拒绝支付。符合取消条件的企业余额订单按原币种退款，重复请求不重复退款。

真实支付未配置时，用户可提交转账凭证，由财务核款。此路径不生成伪造的在线收款二维码。付款成功后，订单推进到等待寄入；已存在有效在线支付时不允许同时改为钱包或人工收款。

渠道确认退款或撤销后，订单进入 `payment_exception`（付款异常待核查）并暂停后续履约，客户页提示联系支持。管理端订单列表可以筛选并在详情中查看该状态，但普通订单状态下拉不能设置或解除此暂停；财务必须先核查退款、撤销、订单金额和相关流水，再通过专用异常处置完成后续处理。

用户订单页按“下单、付款、寄入、收货、评级、复核封装、回寄、签收”显示进度，寄入与回寄运单、轨迹和时间分别展示；每 20 秒自动刷新。物流轨迹由 Java 管理端录入，尚未接入承运商自动查件 API。寄入签收不会误标为整单完成；迟到的扫描事件不会倒退已签收状态。

邮件验证、密码重置和订单状态通知已经具备接口及加密 outbox。保持 `NXR_NOTIFICATION_DELIVERY_ENABLED=false`，直到后续配置并验收事务邮件服务。启用还需独立 `NXR_NOTIFICATION_PAYLOAD_KEY`（至少 32 字符）、现有 `NXR_RESEND_KEY` 或 SMTP 配置以及正确的 `nxr.public-site.base-url`。用户密码重置会撤销该账号所有会话；令牌以 SHA-256 存储，限时且只能使用一次。

## 6. 备份恢复演练

恢复必须写入一个全新的本地库，先验证再决定是否替换开发库：

```bash
mysql -uroot -e "CREATE DATABASE nxr_ruoyi_restore_check DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
mysql -uroot nxr_ruoyi_restore_check < local_backups/java_mysql/<timestamp>/nxr_ruoyi.sql
mysqlcheck -uroot nxr_ruoyi_restore_check
```

核对表数量、订单数、金额和价格配置后，删除测试恢复库。任何生产数据库恢复、替换或迁移都必须另行取得当次明确授权。

## 7. 验证命令

### 独立库商业闭环验收

`nxr-scripts/dev-commerce-qa.sh` 用于启动独立的本地商业闭环验收后端，固定监听 `127.0.0.1:8090`，并把数据库连接限定到名称符合 `nxr_acceptance_*` 的一次性 MySQL 库。它使用 Redis 逻辑库 `14`，关闭 Quartz、真实支付兼容回调和邮件投递。运行前应先完成后端 JAR 构建；本节只说明验收方法，不代表这些命令已经执行。

先从可用于本地测试的 Java 数据库准备一个独立副本，应用当前增量迁移，并确认副本中存在可供关联的 `grading_submission`。不要把 `NXR_QA_DATABASE` 指向 `nxr_ruoyi`、生产库或任何需要保留的业务库。随后在副本的启用中 USD 基础评级价格上写入本次唯一标记，并导出完全相同的变量值：

```bash
export NXR_QA_DATABASE="nxr_acceptance_$(date +%Y%m%d_%H%M%S)"
export NXR_QA_PRICE_MARKER="QA acceptance $(date +%Y%m%d%H%M%S)-${RANDOM}"

mysql -uroot -e "CREATE DATABASE \`${NXR_QA_DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
mysqldump --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF -uroot nxr_ruoyi \
  | mysql -uroot "${NXR_QA_DATABASE}"
mysql -uroot "${NXR_QA_DATABASE}" -e \
  "UPDATE grading_service_price SET display_name='${NXR_QA_PRICE_MARKER}' WHERE price_code='basic_grading' AND currency_code='USD' AND is_active=1"
```

上面的标记只允许使用命令生成的字母、数字、空格和连字符，不要把外部输入直接拼入 SQL。先查询并选择副本中可用于测试的评分记录，再设置其 ID；两个终端必须使用相同的数据库名和唯一标记：

```bash
mysql -uroot "${NXR_QA_DATABASE}" -e "SELECT id FROM grading_submission ORDER BY id LIMIT 10"
export NXR_QA_SUBMISSION_ID="<测试副本中的记录 ID>"

# 终端一：仅启动 8090 验收实例
./nxr-scripts/dev-commerce-qa.sh

# 终端二：保持相同的 NXR_QA_DATABASE、NXR_QA_PRICE_MARKER 和 NXR_QA_SUBMISSION_ID
./nxr-scripts/verify-customer-commerce.py
```

`verify-customer-commerce.py` 在任何登录或写操作之前，先读取 `http://127.0.0.1:8090/api/customer/service-prices`，要求响应包含 `NXR_QA_PRICE_MARKER`。数据库名前缀或唯一标记不匹配时立即终止，避免把仅设置在调用终端的数据库名误当成运行中服务的真实连接。脚本产生的客户、订单、钱包、充值和物流数据均为浏览器复核用的一次性 fixture，不会自动删除。

验收结束后先停止 8090 进程，确认变量仍以 `nxr_acceptance_` 开头且该库确实无需保留，再删除整个测试库。只有在 Redis 逻辑库 `14` 明确专用于本验收、没有其他本地任务使用时，才清理该逻辑库：

```bash
[[ "${NXR_QA_DATABASE}" == nxr_acceptance_* ]] || exit 1
mysql -uroot -e "DROP DATABASE \`${NXR_QA_DATABASE}\`"
redis-cli -n 14 FLUSHDB
unset NXR_QA_DATABASE NXR_QA_PRICE_MARKER NXR_QA_SUBMISSION_ID
```

### 已确认但暂缓的邮箱开通（2026-09-08）

用户选择 Zoho Mail Lite，客服邮箱 `support@nxrgrading.com`。先完成 Java 功能开发，后续再注册、购买和配置 DNS；目前没有开通或改动域名。财务独立账号与 `orders` / `info` 别名待开通时确认。购买时重新核对地区价格和最低席位。订单自动通知使用独立事务邮件发送通道；未配置并显式启用时不发送邮件。

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
