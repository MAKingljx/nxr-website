#!/usr/bin/env python3
"""Partner management and direct/partner card identity acceptance on a marked disposable DB."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import secrets
import urllib.parse
import uuid

spec = importlib.util.spec_from_file_location('agent_qa', Path(__file__).with_name('verify-agent-workbench.py'))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
api, check, sql = qa.api, qa.check, qa.sql


def command(payload):
    return {**payload, 'requestKey': str(uuid.uuid4())}


def main():
    check('runtime marker matches the isolated database before writes', any(x['displayName'] == qa.context['priceMarker'] for x in api('/api/customer/service-prices')))
    admin_token = api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    admin = lambda path, body=None, **kw: api(path, body, admin_token, True, **kw)
    suffix = secrets.token_hex(4)
    password = 'Nxr9!' + secrets.token_hex(6)
    opening = command({'email': 'partner-' + suffix + '@example.invalid', 'displayName': 'QA 子代理',
                       'mobile': '15550100200', 'companyName': 'QA 子代理企业 ' + suffix, 'contactName': 'QA 联系人',
                       'userName': 'partner_' + suffix, 'nickName': 'QA 子代理负责人', 'password': password})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        created = list(pool.map(lambda _: admin('/api/admin/partners', opening), range(2)))
    partner_id, user_id = created[0]['customerId'], created[0]['sysUserId']
    check('concurrent onboarding creates one company and one backend account', all(x['customerId'] == partner_id and x['sysUserId'] == user_id for x in created))
    check('onboarding does not expose password or customer session', all('password' not in json.dumps(x) and 'token' not in json.dumps(x) for x in created))
    api('/api/customer/auth/login', {'email': opening['email'], 'password': password}, blocked={401})
    before = sql('SELECT COUNT(*) FROM customer_account')
    admin('/api/admin/partners', command({**opening, 'email': 'conflict-' + suffix + '@example.invalid'}), blocked={409})
    check('username conflict leaves no orphan company', sql('SELECT COUNT(*) FROM customer_account') == before)
    login = api('/login', {'username': opening['userName'], 'password': password})
    partner_token = login['token']
    partner = lambda path, body=None, **kw: api('/api/admin/agent' + path, body, partner_token, True, **kw)
    api('/api/admin/partners', token=partner_token, admin=True, blocked={403})
    api('/api/admin/partners/' + str(partner_id), token=partner_token, admin=True, blocked={403})
    check('child company operator stays inside its bound submission workspace', partner('/context')['company']['id'] == partner_id and not partner('/context')['platformManager'])
    detail = admin('/api/admin/partners/' + str(partner_id))
    check('platform partner directory exposes company and backend account', detail['partner']['operatorCount'] == 1 and detail['operators'][0]['sysUserId'] == user_id)
    check('partner starts with zero prepaid balance', str(detail['partner']['walletBalance']) in {'0', '0.0', '0.00'})
    recharge_request = command({'currencyCode': 'USD', 'amount': '200', 'providerCode': 'bank_transfer',
                                'payerReference': 'QA-PARTNER-' + suffix, 'proofReference': 'Synthetic test transfer'})
    recharge = admin('/api/admin/partners/' + str(partner_id) + '/wallet-recharges', recharge_request)
    repeated = admin('/api/admin/partners/' + str(partner_id) + '/wallet-recharges', recharge_request)
    check('recharge registration is idempotent and remains pending', recharge['id'] == repeated['id'] and recharge['statusCode'] == 'pending')
    check('unreviewed transfer never increases wallet balance', not partner('/merchant/wallets'))
    confirm = '/api/admin/customers/' + str(partner_id) + '/wallet-recharges/' + str(recharge['id']) + '/confirm'
    review = {'providerTransactionId': 'SYNTHETIC-' + suffix, 'note': 'Synthetic local acceptance only'}
    api(confirm, review, partner_token, True, blocked={403})
    admin(confirm, review)
    admin(confirm, review)
    check('manual finance confirmation credits exactly once in the same currency', partner('/merchant/wallets')[0]['balance'] == 200 and len(partner('/merchant/wallets')) == 1)
    admin('/api/admin/partners/' + str(partner_id) + '/wallet-recharges', {**recharge_request, 'amount': '201'}, blocked={409})

    customers = [api('/api/customer/auth/register', {'email': 'direct-' + str(i) + '-' + suffix + '@example.invalid', 'displayName': 'QA 同名客户', 'password': password}) for i in range(2)]
    option = api('/api/customer/shipping-options?country=US&currencyCode=USD')[0]['optionCode']
    direct_orders = []
    for customer in customers:
        call = lambda path, body=None, **kw: api(path, body, customer['token'], **kw)
        params = urllib.parse.urlencode({'customerId': customer['customer']['id'], 'country': 'US', 'currency': 'USD', 'count': 2, 'shippingOptionCode': option})
        quote = call('/api/customer/commerce/quote-preview?' + params)
        order = call('/api/customer/orders', {'serviceLevel': 'basic_grading', 'currencyCode': 'USD',
            'returnShippingOptionCode': option, 'contactName': 'QA 同名客户', 'contactPhone': '15550100201',
            'returnAddressLine1': '1 Direct Test Street', 'returnAddressLine2': '', 'returnCity': 'Test City',
            'returnRegion': 'CA', 'returnPostalCode': '90001', 'returnCountry': 'US', 'saveReturnAddress': False,
            'languageGroups': [], 'customerNote': 'Synthetic direct submission',
            'quotedTotalAmount': quote['totalAmount'], 'quotedCurrencyCode': 'USD',
            'items': [{'cardName': 'Pikachu', 'languageCode': 'EN', 'declaredValue': 0} for _ in range(2)]})
        path = '/api/customer/orders/' + order['orderNo'] + '/card-identities'
        before = call(path)
        check('reading direct identities does not allocate or start receiving', all(x['receiptCode'] is None for x in before['items']))
        labels = call(path, {})
        again = call(path, {})
        check('identical physical cards get different permanent receipt codes', len({x['receiptCode'] for x in labels['items']}) == 2)
        check('printing again never renumbers cards', [x['receiptCode'] for x in labels['items']] == [x['receiptCode'] for x in again['items']])
        check('direct cards point to their original customer and direct return route', all(x['sourceType'] == 'direct' and x['ownerKey'] == 'customer:' + str(customer['customer']['id']) and x['returnRoute'] == 'direct_to_customer' and x['partnerCompanyName'] is None for x in labels['items']))
        check('labels do not change order acceptance or payment state', call('/api/customer/orders/' + order['orderNo'])['statusCode'] == order['statusCode'])
        lookup = admin('/api/admin/card-identities/lookup?code=' + labels['items'][0]['receiptCode'])
        check('NXR scan resolves the exact direct order item', lookup['orderItemId'] == labels['items'][0]['orderItemId'] and lookup['orderId'] == order['id'])
        partner('/orders/' + order['orderNo'] + '/card-identities', blocked={404})
        api('/api/admin/card-identities/lookup?code=' + labels['items'][0]['receiptCode'], token=partner_token, admin=True, blocked={403})
        direct_orders.append({'order': order, 'labels': labels, 'customer': customer})
    check('same-named customers never share card ownership', direct_orders[0]['labels']['items'][0]['ownerKey'] != direct_orders[1]['labels']['items'][0]['ownerKey'])
    api('/api/customer/orders/' + direct_orders[0]['order']['orderNo'] + '/card-identities', token=customers[1]['token'], blocked={404})

    client = partner('/clients', command({'reference': 'OWNER-' + suffix, 'displayName': 'QA 子代理客户',
        'contactName': 'QA 子代理客户', 'phone': '15550100202', 'email': '', 'addressLine1': '1 Client Street',
        'addressLine2': '', 'city': 'Test City', 'region': 'CA', 'postalCode': '90001', 'country': 'US', 'active': True, 'notes': ''}))
    intake = partner('/intakes', command({'clientId': client['id'], 'carrierName': 'Client test carrier',
        'trackingNumber': 'CLIENT-IN-' + suffix, 'expectedCardCount': 2,
        'cards': [{'cardName': 'Pikachu', 'languageCode': 'EN', 'notes': ''} for _ in range(2)]}))
    partner('/intakes/' + str(intake['intake']['id']) + '/receive', command({'note': 'Synthetic receipt'}))
    for card in intake['cards']:
        partner('/intakes/' + str(intake['intake']['id']) + '/check-in', command({'inventoryCode': card['inventoryCode'], 'hasException': False}))
    address = partner('/addresses', {'label': 'QA 子代理地址', 'contactName': 'QA 子代理', 'contactPhone': '15550100203',
        'addressLine1': '10 Partner Street', 'addressLine2': '', 'city': 'Test City', 'region': 'CA', 'postalCode': '90001', 'country': 'US', 'defaultAddress': True})
    submitted = partner('/submissions', command({'intakeIds': [intake['intake']['id']], 'returnAddressId': address['id'],
        'returnShippingOptionCode': option, 'currencyCode': 'USD', 'batchName': 'QA 子代理合寄 ' + suffix}))
    batch = partner('/merchant/batches/' + submitted['batchNo'])
    order_no = batch['orders'][0]['orderNo']
    code = intake['cards'][0]['inventoryCode']
    found = admin('/api/admin/card-identities/lookup?code=' + code)
    check('NXR can identify a partner intake label before physical reallocation', found['ownerDisplayName'] == client['displayName'] and found['sourceType'] == 'partner' and found['batchNo'] == submitted['batchNo'])
    labels = partner('/orders/' + order_no + '/card-identities', {})
    check('partner cards keep their intake codes across the NXR handoff', {x['receiptCode'] for x in labels['items']} == {x['inventoryCode'] for x in intake['cards']})
    check('new partner physical labels reuse the receipt identity', all(x['physicalBarcode'] == x['receiptCode'] for x in labels['items']))
    check('partner labels retain original client and return via the correct company', all(x['ownerDisplayName'] == client['displayName'] and x['clientReference'] == client['reference'] and x['partnerCompanyName'] == opening['companyName'] and x['returnRoute'] == 'via_partner' for x in labels['items']))
    current = admin('/api/admin/partners/' + str(partner_id))
    check('platform partner summary reflects collected clients and outstanding batch', current['partner']['clientCount'] == 1 and current['partner']['pendingBatchCount'] == 1)
    token_result = partner('/merchant/batches/' + submitted['batchNo'] + '/orders/' + order_no + '/tracking-token/rotate', {})
    check('private query uses the actual customer site route, not the admin origin', token_result['trackingUrl'] == 'http://127.0.0.1:3002/track/' + token_result['trackingToken'])
    public_path = '/api/public/merchant-order-tracking/' + token_result['trackingToken']
    public = api(public_path)
    check('private tracking shows the original child only', public['orderNo'] == order_no and 'totalAmount' not in public and 'customerEmail' not in public)
    partner('/merchant/batches/' + submitted['batchNo'] + '/orders/' + order_no + '/tracking-token', method='DELETE')
    api(public_path, blocked={404})
    profile = {k: current['partner'][k] for k in ['displayName', 'email', 'mobile', 'companyName', 'contactName']}
    admin('/api/admin/partners/' + str(partner_id), {**profile, 'active': False}, method='PUT')
    partner('/context', blocked={403})
    check('disabled partner remains visible to platform administrators', not admin('/api/admin/partners/' + str(partner_id))['partner']['active'])
    admin('/api/admin/partners/' + str(partner_id), {**profile, 'active': True}, method='PUT')
    check('reactivation retains customer and inventory ownership', partner('/clients')['total'] == 1)
    check('real payment providers remain disabled', api('/api/customer/payment-options?currency=USD') == [])
    saved = {'partnerId': partner_id, 'operator': {'userId': user_id, 'userName': opening['userName'], 'password': password, 'token': partner_token},
             'adminToken': admin_token, 'directOrders': direct_orders, 'partnerOrderNo': order_no, 'partnerBatchNo': submitted['batchNo'], 'partnerLabels': labels}
    state = Path('/tmp') / (qa.database + '-partner-identity-session.json')
    state.write_text(json.dumps(saved)); state.chmod(0o600)
    result = {'database': qa.database, 'passed': len(qa.checks), 'checks': qa.checks, 'realPayments': 0, 'emailsSent': 0}
    output = Path('/tmp') / (qa.database + '-partner-identity-results.json')
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n'); output.chmod(0o600)
    print(json.dumps({'passed': len(qa.checks), 'realPayments': 0, 'emailsSent': 0}))


if __name__ == '__main__':
    main()
