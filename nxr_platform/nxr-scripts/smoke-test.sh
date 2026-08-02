#!/usr/bin/env bash

# 若依版 NXR 平台全链路冒烟测试。
# 用法：./smoke-test.sh [BASE_URL]，默认 http://127.0.0.1:8088

set -uo pipefail

BASE="${1:-http://127.0.0.1:8088}"
PASS=0
FAIL=0

check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == *"$expected"* ]]; then
    echo "PASS  $name"
    PASS=$((PASS + 1))
  else
    echo "FAIL  $name  期望包含 [$expected]，实际 [$actual]"
    FAIL=$((FAIL + 1))
  fi
}

# ---------- 公开接口（匿名） ----------
check "平台健康检查" '"status":"ok"' "$(curl -s "$BASE/api/platform/health")"
check "公开概览" '"publishedCertificates"' "$(curl -s "$BASE/api/public/overview")"
check "Waitlist 计数" '"count"' "$(curl -s "$BASE/api/public/waitlist-count")"
check "证书查询（大写）" '"certId":"VRA003"' "$(curl -s "$BASE/api/public/cards/VRA003")"
check "证书查询（小写）" '"certId":"VRA003"' "$(curl -s "$BASE/api/public/cards/vra003")"
check "缺失证书 404" "404" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/public/cards/NOPE123")"
check "SSE 流式接口" "event: chunk" "$(curl -s -N -m 8 -X POST "$BASE/api/public/ai-character-info/stream" -H 'Content-Type: application/json' -d '{"certId":"VRA003","character":"Umbreon","language":"en"}' | head -c 200)"

# ---------- 认证 ----------
check "未登录拦截" '"code":401' "$(curl -s "$BASE/api/admin/submissions")"

TOKEN=$(curl -s -X POST "$BASE/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
if [ -z "$TOKEN" ]; then
  echo "FAIL  登录失败，无法继续管理接口测试"
  exit 1
fi
echo "PASS  登录获取 token"
PASS=$((PASS + 1))
H="Authorization: Bearer $TOKEN"

# ---------- 管理接口 ----------
check "仪表盘" '"totalSubmissions"' "$(curl -s "$BASE/api/admin/dashboard" -H "$H")"
check "录入列表" '"items"' "$(curl -s "$BASE/api/admin/submissions?page=1&pageSize=3" -H "$H")"
check "生成证书编号" '"certId"' "$(curl -s "$BASE/api/admin/submissions/generate-cert-id" -H "$H")"
check "评级计算" '"finalGradeLabel"' "$(curl -s -X POST "$BASE/api/admin/submissions/calculate-grade" -H "$H" -H 'Content-Type: application/json' -d '{"centeringScore":9.5,"edgesScore":9,"cornersScore":9.5,"surfaceScore":10}')"
check "品牌列表" '"name":"Pokemon"' "$(curl -s "$BASE/api/admin/brand-settings" -H "$H")"
check "Waitlist 列表" '"items"' "$(curl -s "$BASE/api/admin/waitlist?page=1&pageSize=3" -H "$H")"
check "媒体队列" '"summary"' "$(curl -s "$BASE/api/admin/media/queue?page=1&pageSize=3" -H "$H")"
check "导出预览" '"canExport"' "$(curl -s -X POST "$BASE/api/admin/exports/preview" -H "$H" -H 'Content-Type: application/json' -d '{"gradeFilter":"","certIds":""}')"
check "运动类型字典" '"Basketball"' "$(curl -s "$BASE/system/dict/data/type/nxr_sports_type" -H "$H")"
check "客户账号管理列表" '"items"' "$(curl -s "$BASE/api/admin/customers?page=1&pageSize=3" -H "$H")"

# ---------- 客户账号、卡片归属与传递记录 ----------
SMOKE_SUFFIX="$(date +%s)"
CUSTOMER_EMAIL="smoke.${SMOKE_SUFFIX}@example.test"
CUSTOMER_AUTH=$(curl -s -X POST "$BASE/api/customer/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$CUSTOMER_EMAIL\",\"password\":\"LocalPass123!\",\"displayName\":\"Smoke Customer\"}")
CUSTOMER_TOKEN=$(printf '%s' "$CUSTOMER_AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
CUSTOMER_ID=$(printf '%s' "$CUSTOMER_AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('customer',{}).get('id',''))")
if [ -z "$CUSTOMER_TOKEN" ]; then
  echo "FAIL  客户注册失败，无法继续客户流程测试"
  exit 1
fi
echo "PASS  客户注册获取会话"
PASS=$((PASS + 1))
CH="X-NXR-Customer-Token: $CUSTOMER_TOKEN"

RECIPIENT_EMAIL="recipient.${SMOKE_SUFFIX}@example.test"
RECIPIENT_AUTH=$(curl -s -X POST "$BASE/api/customer/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$RECIPIENT_EMAIL\",\"password\":\"LocalPass123!\",\"displayName\":\"Smoke Recipient\"}")
RECIPIENT_TOKEN=$(printf '%s' "$RECIPIENT_AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
RECIPIENT_ID=$(printf '%s' "$RECIPIENT_AUTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('customer',{}).get('id',''))")
if [ -z "$RECIPIENT_TOKEN" ] || [ -z "$CUSTOMER_ID" ] || [ -z "$RECIPIENT_ID" ]; then
  echo "FAIL  接收方注册或客户编号缺失，无法继续卡片传递测试"
  exit 1
fi
echo "PASS  接收方注册获取会话"
PASS=$((PASS + 1))
RH="X-NXR-Customer-Token: $RECIPIENT_TOKEN"

check "客户绑定公开卡片" '"ownerLabel":"Smoke Customer"' "$(curl -s -X POST "$BASE/api/customer/cards/VRA003/claim" -H "$CH" -H 'Content-Type: application/json' -d '{"visibility":"public","note":"smoke first owner"}')"
check "他人重复绑定拦截" "409" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/customer/cards/VRA003/claim" -H "$RH" -H 'Content-Type: application/json' -d '{"visibility":"public","note":"duplicate"}')"
TRANSFER_BODY=$(python3 -c 'import json,sys; print(json.dumps({"recipientEmail":sys.argv[1],"visibility":"public","message":"smoke handoff"}))' "$RECIPIENT_EMAIL")
check "当前持有人传递卡片" '"ownerLabel":"Smoke Recipient"' "$(curl -s -X POST "$BASE/api/customer/cards/VRA003/transfer" -H "$CH" -H 'Content-Type: application/json' -d "$TRANSFER_BODY")"
check "公开查看传递历史" '"eventTypeCode":"transferred"' "$(curl -s "$BASE/api/customer/cards/VRA003/community")"
check "原持有人卡片列表已释放" '[]' "$(curl -s "$BASE/api/customer/cards" -H "$CH")"
check "接收方卡片列表已更新" '"certId":"VRA003"' "$(curl -s "$BASE/api/customer/cards" -H "$RH")"
check "评论记忆接口已删除" "No static resource api/customer/cards/VRA003/memories" "$(curl -s -X POST "$BASE/api/customer/cards/VRA003/memories" -H "$H" -H 'Content-Type: application/json' -d '{"body":"removed"}')"
check "后台查看客户详情" "\"email\":\"$CUSTOMER_EMAIL\"" "$(curl -s "$BASE/api/admin/customers/$CUSTOMER_ID" -H "$H")"

# ---------- 客户门户与送评订单闭环 ----------
CUSTOMER_ORDER=$(curl -s -X POST "$BASE/api/customer/orders" -H "$CH" -H 'Content-Type: application/json' \
  -d '{"serviceLevel":"standard","contactName":"Smoke Customer","contactPhone":"10000000000","returnAddressLine1":"1 Local Test Way","returnCity":"Shanghai","returnPostalCode":"200000","returnCountry":"CN","items":[{"cardName":"Smoke Test Card","brandName":"Pokemon","setName":"Smoke Set","cardNumber":"001","languageCode":"EN"}]}')
check "客户创建送评单" '"statusCode":"awaiting_payment"' "$CUSTOMER_ORDER"
ORDER_ID=$(printf '%s' "$CUSTOMER_ORDER" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))")
ORDER_NO=$(printf '%s' "$CUSTOMER_ORDER" | python3 -c "import sys,json; print(json.load(sys.stdin).get('orderNo',''))")
PAYMENT_ID=$(printf '%s' "$CUSTOMER_ORDER" | python3 -c "import sys,json; print(json.load(sys.stdin).get('payments',[{}])[0].get('id',''))")
if [ -z "$ORDER_ID" ] || [ -z "$ORDER_NO" ] || [ -z "$PAYMENT_ID" ]; then
  echo "FAIL  送评单返回字段不完整"
  exit 1
fi

check "客户提交付款凭证" '"statusCode":"payment_review"' "$(curl -s -X POST "$BASE/api/customer/orders/$ORDER_NO/payment-proof" -H "$CH" -H 'Content-Type: application/json' -d '{"provider":"manual_transfer","payerReference":"SMOKE-PAYMENT","proofReference":"local"}')"
check "后台确认收款" '"statusCode":"awaiting_inbound"' "$(curl -s -X POST "$BASE/api/admin/orders/$ORDER_ID/payments/$PAYMENT_ID/confirm" -H "$H" -H 'Content-Type: application/json' -d '{"note":"smoke verified"}')"
check "客户登记入库物流" '"statusCode":"inbound_shipped"' "$(curl -s -X POST "$BASE/api/customer/orders/$ORDER_NO/inbound-shipment" -H "$CH" -H 'Content-Type: application/json' -d '{"direction":"inbound","carrierName":"Smoke Express","trackingNumber":"SMOKE-INBOUND","note":"local"}')"
RECEIVED_ORDER=$(curl -s -X POST "$BASE/api/admin/orders/$ORDER_ID/shipments" -H "$H" -H 'Content-Type: application/json' -d '{"direction":"inbound","carrierName":"Smoke Express","trackingNumber":"SMOKE-INBOUND","note":"received"}')
check "后台确认收卡" '"statusCode":"received"' "$RECEIVED_ORDER"
ITEM_ID=$(printf '%s' "$RECEIVED_ORDER" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['items'][0]['id'])")
check "订单关联评分条目" '"statusCode":"grading"' "$(curl -s -X POST "$BASE/api/admin/orders/$ORDER_ID/items/$ITEM_ID/link-submission?submissionId=1004" -H "$H")"
check "订单进入复核" '"statusCode":"review"' "$(curl -s -X POST "$BASE/api/admin/orders/$ORDER_ID/status" -H "$H" -H 'Content-Type: application/json' -d '{"statusCode":"review","detail":"smoke review"}')"
check "订单评分完成" '"statusCode":"completed"' "$(curl -s -X POST "$BASE/api/admin/orders/$ORDER_ID/status" -H "$H" -H 'Content-Type: application/json' -d '{"statusCode":"completed","detail":"smoke complete"}')"
OUTBOUND_ORDER=$(curl -s -X POST "$BASE/api/admin/orders/$ORDER_ID/shipments" -H "$H" -H 'Content-Type: application/json' -d '{"direction":"outbound","carrierName":"Smoke Express","trackingNumber":"SMOKE-RETURN","note":"returned"}')
check "后台登记回寄物流" '"statusCode":"return_shipped"' "$OUTBOUND_ORDER"
OUTBOUND_SHIPMENT_ID=$(printf '%s' "$OUTBOUND_ORDER" | python3 -c "import sys,json; print(next(item['id'] for item in json.load(sys.stdin)['data']['shipments'] if item['directionCode']=='outbound'))")
check "后台确认回寄签收" '"statusCode":"delivered"' "$(curl -s -X POST "$BASE/api/admin/orders/$ORDER_ID/shipments/$OUTBOUND_SHIPMENT_ID/delivered" -H "$H")"
check "客户查看完成订单" '"statusCode":"delivered"' "$(curl -s "$BASE/api/customer/orders/$ORDER_NO" -H "$CH")"
check "后台撤销客户会话" "\"customerId\":$RECIPIENT_ID" "$(curl -s -X POST "$BASE/api/admin/customers/$RECIPIENT_ID/sessions/revoke" -H "$H")"
check "已撤销会话拒绝访问" "401" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/customer/auth/me" -H "$RH")"

echo
echo "结果：$PASS 通过，$FAIL 失败"
[ "$FAIL" -eq 0 ]
