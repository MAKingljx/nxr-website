#!/usr/bin/env python3
"""End-to-end agent intake/return acceptance on a marked disposable localhost DB."""
import concurrent.futures
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import secrets
import struct
import subprocess
import urllib.error
import urllib.request
import uuid
import zlib

database = os.environ.get('NXR_QA_DATABASE', '')
assert re.fullmatch(r'nxr_acceptance_agent_[a-zA-Z0-9_]+', database), 'Disposable agent QA database required'
context = json.loads((Path('/tmp') / f'{database}-context.json').read_text())
assert context['database'] == database and context['baseUrl'] == 'http://127.0.0.1:8090'
BASE = context['baseUrl']
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
checks = []


def check(name, condition):
    assert condition, name
    checks.append(name)


def api(path, payload=None, token=None, admin=False, method=None, blocked=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization' if admin else 'X-NXR-Customer-Token'] = ('Bearer ' if admin else '') + token
    req = urllib.request.Request(BASE + path, data=None if payload is None else json.dumps(payload).encode(),
                                 headers=headers, method=method or ('POST' if payload is not None else 'GET'))
    try:
        with http.open(req, timeout=40) as response:
            status, data = response.status, json.load(response)
    except urllib.error.HTTPError as response:
        status, data = response.code, json.load(response)
    code = data.get('code', status) if isinstance(data, dict) else status
    if blocked:
        check(f'blocked {method or "request"} {path}', code in blocked)
        return data
    assert status < 400 and code == 200, f'{path}: {status}/{code}: {data.get("message", data.get("msg", ""))}'
    return data['data'] if isinstance(data, dict) and 'code' in data and 'data' in data else data


def sql(statement):
    return subprocess.check_output(['mysql', '-uroot', '-N', database, '-e', statement], text=True).strip()


def key(payload):
    return {**payload, 'requestKey': str(uuid.uuid4())}


def png():
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 32, 48, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\x40\x80\xb0' * 32) * 48)) + chunk(b'IEND', b'')


def upload(path, token, expected=200):
    boundary = 'AgentFixture' + secrets.token_hex(8)
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
            + png() + f'\r\n--{boundary}--\r\n'.encode())
    req = urllib.request.Request(BASE + path, data=body, headers={'Content-Type': f'multipart/form-data; boundary={boundary}', 'X-NXR-Customer-Token': token})
    try:
        with http.open(req, timeout=30) as response:
            status, data = response.status, json.load(response)
    except urllib.error.HTTPError as response:
        status, data = response.code, json.load(response)
    assert data.get('code', status) == expected, f'photo upload response {status}'
    return data.get('data', data)


def main():
    check('runtime belongs to the new isolated database', any(x['displayName'] == context['priceMarker'] for x in api('/api/customer/service-prices')))
    suffix = secrets.token_hex(4)
    admin_token = api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    admin = lambda path, payload=None, **kw: api(path, payload, admin_token, True, **kw)
    admin('/api/admin/commerce-policy/business-line', {'lineCode': 'qa_agent_' + suffix, 'displayName': 'QA agent submissions', 'orderOriginCode': 'customer_submission', 'defaultLine': True, 'active': True}, method='PUT')
    admin('/api/admin/commerce-policy/work-center', {'centerCode': 'qa_agent_' + suffix, 'displayName': 'QA agent processing', 'defaultCenter': True, 'active': True}, method='PUT')
    accounts = []
    password = 'Agent-QA-' + secrets.token_urlsafe(18)
    for role in ['agent-a', 'agent-b', 'collector']:
        account = api('/api/customer/auth/register', {'email': f'{role}-{suffix}@example.invalid', 'password': password, 'displayName': 'QA ' + role})
        if role != 'collector':
            admin(f'/api/admin/customers/{account["customer"]["id"]}/type', {'accountTypeCode': 'merchant'}, method='PUT')
            account['customer']['accountTypeCode'] = 'merchant'
        accounts.append(account)
    agent, rival, collector = accounts
    customer = lambda path, payload=None, **kw: api(path, payload, agent['token'], **kw)
    other = lambda path, payload=None, **kw: api(path, payload, rival['token'], **kw)
    prefix = '/api/customer/agent'
    api(prefix + '/clients', token=collector['token'], blocked={403})
    api(prefix + '/clients', blocked={401, 403})
    check('no payment provider is enabled', api('/api/customer/payment-options?currency=USD') == [])
    check('mail delivery remains disabled', not customer('/api/customer/account/email-status')['delivery']['available'])
    client_base = {'reference': 'ALICE', 'displayName': 'QA Alice', 'contactName': 'QA Alice', 'phone': '15550100001',
                   'email': 'alice@example.invalid', 'addressLine1': '1 Client Street', 'addressLine2': '',
                   'city': 'Test City', 'region': 'CA', 'postalCode': '90001', 'country': 'US', 'notes': 'Synthetic client', 'active': True}
    first_request = key(client_base)
    alice = customer(prefix + '/clients', first_request)
    check('client creation retry returns the same record', customer(prefix + '/clients', first_request)['id'] == alice['id'])
    customer(prefix + '/clients', {**first_request, 'displayName': 'Changed'}, blocked={409})
    bob = customer(prefix + '/clients', key({**client_base, 'reference': 'BOB', 'displayName': 'QA Bob'}))
    other_alice = other(prefix + '/clients', key(client_base))
    check('client references are scoped to each agent', alice['id'] != other_alice['id'])
    other(prefix + f'/clients/{alice["id"]}', blocked={404})
    other(prefix + f'/clients/{alice["id"]}', client_base, method='PUT', blocked={404})
    check('customer directories are isolated', other(prefix + '/clients')['total'] == 1)
    cards_a = [{'cardName': 'Same card name', 'languageCode': 'EN', 'notes': 'Alice card 1'}, {'cardName': 'Same card name', 'languageCode': 'EN', 'notes': 'Alice card 2'}]
    intake_request = key({'clientId': alice['id'], 'carrierName': 'Client Carrier', 'trackingNumber': 'CLIENT-A-' + suffix, 'expectedCardCount': 2, 'notes': 'Inner package A', 'cards': cards_a})
    bad = key({**intake_request, 'expectedCardCount': 3})
    customer(prefix + '/intakes', bad, blocked={400})
    intake_a = customer(prefix + '/intakes', intake_request)
    check('intake retry does not duplicate stock cards', customer(prefix + '/intakes', intake_request)['intake']['id'] == intake_a['intake']['id'])
    intake_b = customer(prefix + '/intakes', key({**intake_request, 'clientId': bob['id'], 'trackingNumber': 'CLIENT-B-' + suffix, 'expectedCardCount': 1, 'cards': cards_a[:1]}))
    aid, bid = intake_a['intake']['id'], intake_b['intake']['id']
    all_cards = intake_a['cards'] + intake_b['cards']
    check('same-named physical cards have unique inventory identifiers', len({x['inventoryCode'] for x in all_cards}) == 3)
    other(prefix + f'/intakes/{aid}', blocked={404})
    other(prefix + f'/intakes/{aid}/receive', key({'note': 'forbidden'}), blocked={404})
    customer(prefix + f'/intakes/{aid}/check-in', key({'inventoryCode': all_cards[0]['inventoryCode'], 'hasException': False}), blocked={409})
    for intake in [aid, bid]: customer(prefix + f'/intakes/{intake}/receive', key({'note': 'Package signed for at agent'}))
    customer(prefix + f'/intakes/{aid}/check-in', key({'inventoryCode': all_cards[2]['inventoryCode'], 'hasException': False}), blocked={404, 409})
    address = customer('/api/customer/addresses', {'label': 'Agent warehouse', 'contactName': 'QA Agent', 'contactPhone': '15550100999', 'addressLine1': '10 Agent Road', 'addressLine2': '', 'city': 'Test City', 'region': 'CA', 'postalCode': '90002', 'country': 'US', 'defaultAddress': True})
    option = api('/api/customer/shipping-options?country=US&currencyCode=USD')[0]['optionCode']
    submission = key({'intakeIds': [aid, bid], 'returnAddressId': address['id'], 'returnShippingOptionCode': option, 'currencyCode': 'USD', 'batchName': 'Agent QA ' + suffix})
    customer(prefix + '/submissions', submission, blocked={409})
    old_exception = None
    for card in all_cards:
        command = key({'inventoryCode': card['inventoryCode'], 'conditionNote': 'Count matches', 'hasException': card['id'] == all_cards[1]['id']})
        customer(prefix + f'/intakes/{card["intakeId"]}/check-in', command)
        if command['hasException']: old_exception = command
    customer(prefix + '/submissions', submission, blocked={409})
    customer(prefix + f'/intakes/{aid}/check-in', key({'inventoryCode': all_cards[1]['inventoryCode'], 'conditionNote': 'Exception resolved with client', 'hasException': False}))
    check('replaying an older check-in cannot undo a later correction', customer(prefix + f'/intakes/{aid}/check-in', old_exception)['intake']['statusCode'] == 'ready')
    customer(prefix + f'/intakes/{aid}/check-in', {**old_exception, 'conditionNote': 'Changed stale payload'}, blocked={409})
    uploaded = upload(prefix + f'/cards/{all_cards[0]["id"]}/photos?side=front', agent['token'])
    photo_id = uploaded['frontPhotoId']; check('agent intake photo is attached to its card', photo_id is not None)
    customer(f'/api/customer/order-photos/{photo_id}', method='DELETE', blocked={409})
    other(f'/api/customer/order-photos/{photo_id}', blocked={404})
    upload(prefix + f'/cards/{all_cards[0]["id"]}/photos?side=back', rival['token'], 404)
    customer(prefix + '/submissions', key({**submission, 'intakeIds': [aid, aid]}), blocked={400})
    customer(prefix + '/submissions', key({**submission, 'quotedTotalAmount': '0.01', 'quotedCurrencyCode': 'USD'}), blocked={409})
    check('stale quote leaves no NXR batch or stock linkage', customer(prefix + f'/intakes/{aid}')['intake']['orderId'] is None and customer('/api/customer/merchant/batches')['total'] == 0)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: customer(prefix + '/submissions', submission), range(2)))
    created = results[0]; check('concurrent submission creates only one NXR batch', results[1]['batchId'] == created['batchId'])
    customer(prefix + '/submissions', key(submission), blocked={409})
    batch_no, batch_id = created['batchNo'], created['batchId']
    batch = customer('/api/customer/merchant/batches/' + batch_no)
    check('client intakes map to distinct children in one existing batch', len(batch['orders']) == 2 and {x['clientDisplayName'] for x in batch['orders']} == {'QA Alice', 'QA Bob'})
    stock = customer(prefix + '/cards?pageSize=100')['items']
    check('every inventory card maps to a unique grading order item', len({x['orderItemId'] for x in stock}) == 3 and all(x['orderItemId'] for x in stock))
    photo_order = customer('/api/customer/orders/' + next(x['orderNo'] for x in stock if x['id'] == all_cards[0]['id']))
    check('intake photo follows the correct submitted order item', any(x['frontPhotoId'] == photo_id for x in photo_order['items']))
    customer(prefix + f'/cards/{stock[0]["id"]}/return-check', key({'inventoryCode': stock[0]['inventoryCode']}), blocked={409})
    empty_shipment = key({'clientId': alice['id'], 'cardIds': [stock[0]['id']], 'carrierName': 'Final Carrier', 'trackingNumber': 'FINAL-' + suffix})
    customer(prefix + '/return-shipments', empty_shipment, blocked={409})
    recharge = customer('/api/customer/merchant/wallet-recharges', {'currencyCode': 'USD', 'amount': '1000', 'providerCode': 'bank_transfer', 'payerReference': 'QA-' + suffix})
    admin(f'/api/admin/customers/{agent["customer"]["id"]}/wallet-recharges/{recharge["id"]}/confirm', {'providerTransactionId': 'QA-SIMULATED-' + suffix, 'note': 'Simulated transfer only in disposable database'})
    inbound_payload = {'carrierName': 'Agent to NXR', 'trackingNumber': 'MASTER-IN-' + suffix}
    customer(f'/api/customer/merchant/batches/{batch_no}/inbound-shipment', inbound_payload, blocked={409})
    for order in batch['orders']:
        admission = admin(f'/api/admin/order-admissions/{order["orderId"]}')
        approved = admin(f'/api/admin/order-admissions/{order["orderId"]}/decision', {'decision': 'approve', 'expectedRevision': admission['admissionRevision'], 'note': 'QA accepted'})
        detail = customer('/api/customer/orders/' + order['orderNo'])
        customer(f'/api/customer/orders/{order["orderNo"]}/admission/accept-terms', {'termsVersion': approved['termsVersion'], 'acceptedQuotedAmount': detail['totalAmount'], 'acceptedCurrency': 'USD'})
        customer(f'/api/customer/orders/{order["orderNo"]}/wallet-payment', {'idempotencyKey': 'qa-agent-pay-' + order['orderNo']})
    inbound = customer(f'/api/customer/merchant/batches/{batch_no}/inbound-shipment', inbound_payload)
    main_in = next(x for x in inbound['shipments'] if x['directionCode'] == 'inbound')
    admin(f'/api/admin/merchant-batches/{batch_id}/shipments/{main_in["id"]}/delivered', {})
    certs = {}; cert_seed = secrets.randbelow(500000000) + 9000000000
    for order in batch['orders']:
        path = f'/api/admin/orders/{order["orderId"]}'
        detail = admin(path); bench = admin(path + '/workbench/start', {}); sid = bench['activeSession']['id']
        for item in bench['items']: admin(path + '/workbench/scan', {'sessionId': sid, 'stage': 'intake', 'barcode': item['barcode']})
        admin(path + '/intake/receive', {'intakeCode': detail['intakeCode'], 'packageNo': 'INNER-' + str(order['orderId']), 'receivedCount': detail['totalCardCount'], 'conditionNote': 'Agent parcel complete', 'exceptionTypes': []})
        for item in bench['items']:
            cert = str(cert_seed); cert_seed += 1; certs[item['orderItemId']] = cert
            payload = {'certId': cert, 'productType': 'graded_card', 'cardCategory': 'trading_card', 'cardName': 'Same card name', 'yearLabel': '2026', 'brandName': 'QA', 'setName': 'QA Agent', 'cardNumber': '1', 'languageCode': 'EN', 'centeringScore': '9', 'edgesScore': '9', 'cornersScore': '9', 'surfaceScore': '9', 'entryNotes': 'Isolated fixture'}
            graded = admin(path + f'/items/{item["orderItemId"]}/submission', payload)
            admin(f'/api/admin/submissions/{graded["submission"]["id"]}/approve', {})
        for task in admin(path + '/operations')['workTasks']:
            if task['taskTypeCode'] in {'preprocess', 'vision', 'manual_review', 'encapsulation'} and task['statusCode'] != 'completed':
                admin(path + f'/tasks/{task["id"]}', {'statusCode': 'completed', 'resultSummary': 'QA task complete'})
        admin(path + '/status', {'statusCode': 'review', 'detail': 'QA review'})
        admin(path + '/quality-check', {'passed': True, 'note': 'QA final check'})
        req = urllib.request.Request(BASE + path + '/workbench/label-export', data=b'{}', headers={'Authorization': 'Bearer ' + admin_token, 'Content-Type': 'application/json'})
        with http.open(req, timeout=30) as response: response.read()
        for item in admin(path + '/workbench')['items']:
            for stage in ['label', 'packing']: admin(path + '/workbench/scan', {'sessionId': sid, 'stage': stage, 'barcode': item['labelBarcode']})
        admin(path + '/workbench/packing-check', {'sessionId': sid})
    outbound = admin(f'/api/admin/merchant-batches/{batch_id}/outbound-shipment', {'carrierName': 'NXR to Agent', 'trackingNumber': 'MASTER-OUT-' + suffix})
    stock = customer(prefix + '/cards?pageSize=100')['items']
    customer(prefix + f'/cards/{stock[0]["id"]}/return-check', key({'inventoryCode': stock[0]['inventoryCode']}), blocked={409})
    out = next(x for x in outbound['shipments'] if x['directionCode'] == 'outbound')
    admin(f'/api/admin/merchant-batches/{batch_id}/shipments/{out["id"]}/delivered', {})
    for index, card in enumerate(stock):
        wrong = next(x['inventoryCode'] for x in stock if x['id'] != card['id'])
        customer(prefix + f'/cards/{card["id"]}/return-check', key({'inventoryCode': wrong}), blocked={400, 409})
        code = 'https://nxrgrading.com/card/' + certs[card['orderItemId']] if index == 0 else card['inventoryCode']
        returned = customer(prefix + f'/cards/{card["id"]}/return-check', key({'inventoryCode': code, 'note': 'Matched returned physical card'}))
        check('returned card keeps its original customer and item identity', returned['statusCode'] == 'returned' and returned['clientId'] == card['clientId'])
    alice_cards = [x['id'] for x in stock if x['clientId'] == alice['id']]
    bob_cards = [x['id'] for x in stock if x['clientId'] == bob['id']]
    customer(prefix + '/return-shipments', key({**empty_shipment, 'cardIds': [alice_cards[0], bob_cards[0]]}), blocked={400, 409})
    ship_request = key({**empty_shipment, 'cardIds': alice_cards, 'note': 'Return only Alice cards'})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        shipments = list(pool.map(lambda _: customer(prefix + '/return-shipments', ship_request), range(2)))
    sent = shipments[0]['shipment']; check('shipment retry reserves each card only once', sent['id'] == shipments[1]['shipment']['id'] and sent['cardCount'] == 2)
    customer(prefix + '/return-shipments', key(ship_request), blocked={409})
    other(prefix + f'/return-shipments/{sent["id"]}', blocked={404})
    other(prefix + f'/return-shipments/{sent["id"]}/delivered', key({'note': 'forbidden'}), blocked={404})
    customer(prefix + f'/clients/{alice["id"]}', {**client_base, 'addressLine1': 'NEW future address'}, method='PUT')
    check('return shipment preserves its address snapshot', customer(prefix + f'/return-shipments/{sent["id"]}')['shipment']['address']['addressLine1'] == '1 Client Street')
    delivered = customer(prefix + f'/return-shipments/{sent["id"]}/delivered', key({'note': 'Customer signed'}))
    customer(prefix + f'/return-shipments/{sent["id"]}/delivered', key({'note': 'Customer signed'}))
    check('client final delivery updates only selected cards', all(x['statusCode'] == 'delivered' for x in delivered['cards']) and customer(prefix + '/cards?clientId=' + str(bob['id']))['items'][0]['statusCode'] == 'returned')
    history = customer(prefix + f'/clients/{alice["id"]}')
    check('client history joins intake, NXR order and final parcel', history['intakes'] and history['shipments'] and history['events'])
    customer(prefix + f'/clients/{bob["id"]}', {**client_base, 'reference': 'BOB', 'displayName': 'QA Bob', 'active': False}, method='PUT')
    customer(prefix + '/intakes', key({**intake_request, 'clientId': bob['id']}), blocked={400, 409})
    check('archiving a client preserves stock history', len(customer(prefix + f'/clients/{bob["id"]}')['intakes']) == 1)
    check('NXR delivery state was not overwritten by client final-mile events', customer('/api/customer/merchant/batches/' + batch_no)['statusCode'] == 'delivered')
    session_file = Path('/tmp') / f'{database}-session.json'
    session_file.write_text(json.dumps({'agent': agent, 'rival': rival, 'collector': collector, 'adminToken': admin_token, 'clientIds': [alice['id'], bob['id']], 'intakeIds': [aid, bid], 'batchNo': batch_no, 'shipmentId': sent['id']}))
    os.chmod(session_file, 0o600)
    result = {'database': database, 'passed': len(checks), 'checks': checks, 'realPayments': 0, 'providerNetworkCalls': 0, 'emailsSent': 0}
    result_path = Path('/tmp') / f'{database}-results.json'; result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n'); os.chmod(result_path, 0o600)
    print(json.dumps({'passed': len(checks), 'realPayments': 0, 'emailsSent': 0, 'results': str(result_path)}))


if __name__ == '__main__':
    main()
