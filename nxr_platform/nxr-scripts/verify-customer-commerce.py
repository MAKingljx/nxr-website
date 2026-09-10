#!/usr/bin/env python3
"""Exercise Java commerce on the isolated loopback QA service (never production).

Start port 8090 against an nxr_acceptance_* disposable database first. Fixtures
are retained for browser inspection and removed by dropping that test database.
No provider is enabled, no charge or email is sent, and no credential is printed.
"""
import concurrent.futures
import json
import os
import secrets
import urllib.error
import urllib.request
from decimal import Decimal

BASE = 'http://127.0.0.1:8090'
checks = []
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def check(name, condition):
    assert condition, name
    checks.append(name)


def api(path, payload=None, token=None, admin=False, method=None, blocked=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization' if admin else 'X-NXR-Customer-Token'] = ('Bearer ' if admin else '') + token
    req = urllib.request.Request(BASE + path, data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method or ('GET' if payload is None else 'POST'))
    try:
        with http.open(req, timeout=30) as response:
            status, data = response.status, json.load(response)
    except urllib.error.HTTPError as response:
        status, data = response.code, json.load(response)
    code = data.get('code', status) if isinstance(data, dict) else status
    if blocked:
        check(f'blocked {path}: {code}', code in blocked)
        return data
    assert status < 400 and code == 200, f'{path}: {status}/{code}: {str(data.get("message", data.get("msg", "")))[:300]}'
    return data['data'] if isinstance(data, dict) and 'code' in data and 'data' in data else data


def main():
    assert os.environ.get('NXR_QA_DATABASE', '').startswith('nxr_acceptance_'), 'Set disposable QA database marker before running'
    marker = os.environ.get('NXR_QA_PRICE_MARKER', '')
    assert marker.startswith('QA acceptance '), 'Set the unique price marker inserted into the disposable database'
    # This anonymous read must prove the running server uses our database before
    # even login (which may write an audit record) is permitted.
    check('running backend matches the isolated database marker', any(p['displayName'] == marker for p in api('/api/customer/service-prices')))
    suffix = secrets.token_hex(4)
    password = 'Nxr-Qa-Only-2026!'
    email = f'nxr-qa-company-{suffix}@example.invalid'
    admin_token = api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    staff = lambda path, data=None, **kw: api(path, data, admin_token, True, **kw)
    company = api('/api/customer/auth/register', {'email': email, 'password': password, 'displayName': 'NXR QA Company', 'mobile': '+1 555 0100'})
    other = api('/api/customer/auth/register', {'email': f'nxr-qa-other-{suffix}@example.invalid', 'password': password, 'displayName': 'NXR QA Other'})
    customer_id, token = company['customer']['id'], company['token']
    customer = lambda path, data=None, **kw: api(path, data, token, **kw)
    api('/api/customer/merchant/wallets', token=other['token'], blocked={403})
    staff(f'/api/admin/customers/{customer_id}/type', {'accountTypeCode': 'merchant'}, method='PUT')
    profile = customer('/api/customer/merchant/profile', {'companyName': 'NXR QA Company', 'contactName': 'QA Contact'}, method='PUT')
    check('company profile', profile['companyName'] == 'NXR QA Company')
    customer('/api/customer/merchant/wallet-recharges', {'currencyCode': 'JPY', 'amount': '1.50', 'providerCode': 'bank_transfer', 'payerReference': 'invalid precision'}, blocked={400})
    recharge_payload = {'currencyCode': 'USD', 'amount': '1000.00', 'providerCode': 'bank_transfer', 'payerReference': f'QA-{suffix}', 'proofReference': 'Isolated acceptance fixture'}
    recharge = customer('/api/customer/merchant/wallet-recharges', recharge_payload)
    check('recharge remains pending until finance review', recharge['statusCode'] == 'pending')
    path = f'/api/admin/customers/{customer_id}/wallet-recharges/{recharge["id"]}/confirm'
    review = {'providerTransactionId': f'QA-TX-{suffix}', 'note': 'Simulated receipt in disposable QA database'}
    staff(path, review)
    staff(path, review)
    wallets = customer('/api/customer/merchant/wallets')
    check('recharge retry credits only once', Decimal(str(next(w['balance'] for w in wallets if w['currencyCode'] == 'USD'))) == Decimal('1000'))
    duplicate_recharge = customer('/api/customer/merchant/wallet-recharges', recharge_payload)
    staff(f'/api/admin/customers/{customer_id}/wallet-recharges/{duplicate_recharge["id"]}/confirm', review, blocked={409})
    check('unconfigured gateways are hidden', api('/api/customer/payment-options?currency=USD') == [])
    configurations = staff('/api/admin/payment-settings')
    check('all gateways start disabled', all(not c['enabled'] for c in configurations))
    check('notification delivery is off', not customer('/api/customer/account/email-status')['delivery']['available'])
    option = api('/api/customer/shipping-options?country=US&currencyCode=USD')[0]
    order_payload = {'serviceLevel': 'basic_grading', 'currencyCode': 'USD', 'returnShippingOptionCode': option['optionCode'], 'contactName': 'QA Contact', 'contactPhone': '+1 555 0100', 'returnAddressLine1': '1 QA Street', 'returnCity': 'Los Angeles', 'returnRegion': 'CA', 'returnPostalCode': '90001', 'returnCountry': 'US', 'saveReturnAddress': False, 'languageGroups': [{'languageCode': 'EN', 'quantity': 1}], 'customerNote': 'Disposable local Java acceptance test', 'items': []}
    order = customer('/api/customer/orders', order_payload)
    order_no, order_id = order['orderNo'], order['id']
    order_path = f'/api/customer/orders/{order_no}'
    api(order_path, token=other['token'], blocked={404})
    api(order_path + '/operations', token=other['token'], blocked={404})
    api('/api/customer/merchant/orders/bulk', {'sourceName': 'unauthorized.csv', 'orders': [order_payload]}, other['token'], blocked={403})
    customer(order_path + '/checkout', {'provider': 'paypal', 'idempotencyKey': f'qa-disabled-{suffix}'}, blocked={503, 409})
    key = {'idempotencyKey': f'qa-wallet-{suffix}'}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        paid = list(pool.map(lambda _: customer(order_path + '/wallet-payment', key), range(2)))
    check('concurrent wallet payment is idempotent', all(p['statusCode'] == 'awaiting_inbound' for p in paid))
    balance = next(w['balance'] for w in customer('/api/customer/merchant/wallets') if w['currencyCode'] == 'USD')
    check('wallet debited exactly once', Decimal(str(balance)) == Decimal('1000') - Decimal(str(order['totalAmount'])))
    # Different orders still serialize on the same wallet; a stale MySQL
    # REPEATABLE READ snapshot must never overwrite a concurrent debit/refund.
    parallel_orders = [customer('/api/customer/orders', order_payload) for _ in range(2)]
    def pay_parallel(item):
        return customer(f'/api/customer/orders/{item["orderNo"]}/wallet-payment', {'idempotencyKey': f'qa-parallel-{item["orderNo"]}'})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(pay_parallel, parallel_orders))
    parallel_balance = next(w['balance'] for w in customer('/api/customer/merchant/wallets') if w['currencyCode'] == 'USD')
    check('concurrent different orders preserve both debits', Decimal(str(parallel_balance)) == Decimal(str(balance)) - sum(Decimal(str(item['totalAmount'])) for item in parallel_orders))
    def cancel_parallel(item):
        return customer(f'/api/customer/orders/{item["orderNo"]}/cancel', {'reason': 'QA concurrent refund'})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(cancel_parallel, parallel_orders))
    restored_balance = next(w['balance'] for w in customer('/api/customer/merchant/wallets') if w['currencyCode'] == 'USD')
    check('concurrent different orders preserve both refunds', Decimal(str(restored_balance)) == Decimal(str(balance)))
    shipped = customer(order_path + '/inbound-shipment', {'direction': 'inbound', 'carrierName': 'QA Express', 'trackingNumber': f'QA-IN-{suffix}', 'note': 'Incoming parcel'})
    inbound = next(s for s in shipped['shipments'] if s['directionCode'] == 'inbound')
    staff(f'/api/admin/orders/{order_id}/shipments/{inbound["id"]}/tracking', {'eventCode': 'delivered', 'eventDetail': 'Parcel signed for by NXR warehouse'})
    incoming = customer(order_path)
    check('incoming delivery does not finish the order', incoming['statusCode'] == 'inbound_shipped')
    check('incoming timeline cannot claim final delivery', not any(e['statusCode'] == 'delivered' for e in incoming['timeline']))
    staff(f'/api/admin/orders/{order_id}/intake/receive', {'intakeCode': incoming['intakeCode'], 'packageNo': f'QA-PKG-{suffix}', 'receivedCount': 1, 'conditionNote': 'Parcel intact', 'exceptionTypes': []})
    check('intake updates customer status', customer(order_path)['statusCode'] == 'received')
    # The cloned database contains existing submissions; link one copied record.
    submission_id = int(os.environ['NXR_QA_SUBMISSION_ID'])
    staff(f'/api/admin/orders/{order_id}/items/{order["items"][0]["id"]}/link-submission?submissionId={submission_id}', {})
    check('grading is visible to customer', customer(order_path)['statusCode'] == 'grading')
    operations = staff(f'/api/admin/orders/{order_id}/operations')
    for task in operations['workTasks']:
        staff(f'/api/admin/orders/{order_id}/tasks/{task["id"]}', {'statusCode': 'completed', 'resultSummary': 'QA completed', 'failureReason': ''})
    staff(f'/api/admin/orders/{order_id}/status', {'statusCode': 'review', 'detail': 'Review and encapsulation'})
    check('review is visible to customer', customer(order_path)['statusCode'] == 'review')
    staff(f'/api/admin/orders/{order_id}/quality-check', {'passed': True, 'note': 'Final inspection passed'})
    check('quality check awaits dispatch', customer(order_path)['statusCode'] == 'completed')
    outbound = staff(f'/api/admin/orders/{order_id}/shipments', {'direction': 'outbound', 'carrierName': 'QA Return Express', 'trackingNumber': f'QA-OUT-{suffix}', 'note': 'Return parcel'})
    shipment_id = next(s['id'] for s in outbound['shipments'] if s['directionCode'] == 'outbound')
    tracking_path = f'/api/admin/orders/{order_id}/shipments/{shipment_id}/tracking'
    check('dispatch updates customer status', customer(order_path)['statusCode'] == 'return_shipped')
    staff(tracking_path, {'eventCode': 'in_transit', 'locationLabel': 'Return sorting centre', 'eventDetail': 'Parcel in transit'})
    staff(tracking_path, {'eventCode': 'delivered', 'locationLabel': 'Destination', 'eventDetail': 'Signed by customer'})
    staff(tracking_path, {'eventCode': 'in_transit', 'eventDetail': 'Late historical scan'})
    delivered = customer(order_path)
    check('late event cannot regress completed return', delivered['statusCode'] == 'delivered' and next(s['statusCode'] for s in delivered['shipments'] if s['id'] == shipment_id) == 'delivered')
    check('card rows agree with the delivered order', all(item['statusCode'] == 'delivered' for item in delivered['items']))
    directions = {e['directionCode'] for e in customer(order_path + '/operations')['trackingEvents']}
    check('customer sees both tracking directions', directions == {'inbound', 'outbound'})
    refundable = customer('/api/customer/orders', order_payload)
    refund_path = f'/api/customer/orders/{refundable["orderNo"]}'
    customer(refund_path + '/wallet-payment', {'idempotencyKey': f'qa-refund-{suffix}'})
    customer(refund_path + '/cancel', {'reason': 'QA cancellation before shipping'})
    customer(refund_path + '/cancel', {'reason': 'QA duplicate cancellation'})
    refunded_balance = next(w['balance'] for w in customer('/api/customer/merchant/wallets') if w['currencyCode'] == 'USD')
    check('cancellation returns the wallet debit once', Decimal(str(refunded_balance)) == Decimal(str(balance)))
    # Add test-only CNY prices, proving currencies do not share a balance.
    staff('/api/admin/orders/service-price', {'displayName': 'QA CNY grading', 'unitPrice': '150', 'currencyCode': 'CNY'})
    staff('/api/admin/orders/shipping-options', {'optionCode': f'qa_cny_{suffix}', 'displayName': 'QA CNY return', 'description': 'Disposable test option', 'countryScope': 'CN', 'currencyCode': 'CNY', 'priceAmount': '20', 'sortOrder': 99, 'active': True})
    cny = customer('/api/customer/orders', {**order_payload, 'currencyCode': 'CNY', 'returnCountry': 'CN', 'returnShippingOptionCode': f'qa_cny_{suffix}'})
    customer(f'/api/customer/orders/{cny["orderNo"]}/wallet-payment', {'idempotencyKey': f'qa-no-fx-{suffix}'}, blocked={409})
    check('multicurrency quote uses configured currency', cny['currencyCode'] == 'CNY' and Decimal(str(cny['totalAmount'])) == Decimal('170'))
    print(json.dumps({'passed': len(checks), 'checks': checks, 'testCustomerEmail': email, 'deliveredOrderNo': order_no, 'pendingOrderNo': cny['orderNo'], 'testCustomerId': customer_id, 'testDatabase': os.environ['NXR_QA_DATABASE'], 'realPayments': 0, 'emailsSent': 0}, indent=2))


if __name__ == '__main__':
    main()
