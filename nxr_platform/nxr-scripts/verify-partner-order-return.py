#!/usr/bin/env python3
"""Exercise partner Orders, grading and split returns on a marked localhost QA DB.

Creates only synthetic records and leaves labelled examples for GUI review.
All business changes use the real API. SQL is restricted to readback assertions.
"""
import json
import os
from pathlib import Path
import secrets
import struct
import sys
from datetime import datetime
from zoneinfo import ZoneInfo
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

DATABASE = 'nxr_acceptance_agent_orders_20261014'
BASE = 'http://127.0.0.1:8090'
CONTEXT = Path('/tmp') / (DATABASE + '-context.json')
OUTPUT = Path('/tmp/nxr-order-submit-api-20261014.json')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError('QA requests must not redirect away from their localhost endpoint')


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
REPORT = {'database': DATABASE, 'baseUrl': BASE, 'status': 'preparing', 'checks': [], 'orders': [], 'batches': [], 'parcels': []}
TOKEN = None
B_TOKEN = None
COMPANY = 22
PREFIX = 'Local Order Return ' + secrets.token_hex(4)
REPORT['fixturePrefix'] = PREFIX


def sql(statement):
    if not statement.strip().upper().startswith('SELECT ') or ';' in statement:
        raise AssertionError('SQL writes are prohibited in this verifier')
    return subprocess.check_output(['mysql', '-uroot', '--batch', '--skip-column-names', DATABASE, '-e', statement], text=True).strip()


def save():
    OUTPUT.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2))
    OUTPUT.chmod(0o600)


def check(label, condition):
    if not condition:
        raise AssertionError(label)
    REPORT['checks'].append(label)


def guard():
    context = json.loads(CONTEXT.read_text())
    assert context['database'] == DATABASE and context['baseUrl'] == BASE
    marker = context['priceMarker']
    assert marker.startswith('QA acceptance agent ')
    assert sql('SELECT DATABASE()') == DATABASE
    assert marker in sql('SELECT display_name FROM grading_service_price WHERE is_active=1').splitlines()
    with HTTP.open(BASE + '/api/customer/service-prices', timeout=10) as response:
        prices = json.load(response)
    assert any(row['displayName'] == marker for row in prices), 'Backend does not use the marked isolated database'


def api(path, payload=None, *, token=None, owner=None, method=None, blocked=None, binary=False):
    assert path.startswith('/') and not path.startswith('//')
    method = method or ('POST' if payload is not None else 'GET')
    if method not in {'GET', 'HEAD'}:
        guard()
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if owner is not None:
        headers['X-NXR-Agent-Id'] = str(owner)
    request = urllib.request.Request(BASE + path, data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method)
    try:
        with HTTP.open(request, timeout=40) as response:
            status = response.status
            if binary:
                result = response.read()
                assert status == 200 and result, 'Empty label export'
                return result
            data = json.load(response)
    except urllib.error.HTTPError as response:
        status = response.code
        try:
            data = json.load(response)
        except Exception:
            data = {}
    code = data.get('code', status) if isinstance(data, dict) else status
    public_path = '/api/public/merchant-order-tracking/[private]' if path.startswith('/api/public/merchant-order-tracking/') else path
    if blocked is not None:
        check('Rejected ' + method + ' ' + public_path, code in blocked)
        return data
    if status >= 400 or code != 200:
        raise AssertionError(f'{public_path}: HTTP {status}, code {code}, {data.get("message",data.get("msg",""))}')
    return data['data'] if isinstance(data, dict) and 'code' in data and 'data' in data else data


def admin(path, payload=None, **kwargs):
    return api(path, payload, token=TOKEN, **kwargs)


def owner(path, payload=None, company=COMPANY, **kwargs):
    return admin('/api/admin/agent' + path, payload, owner=company, **kwargs)


def keyed(payload):
    return {**payload, 'requestKey': str(uuid.uuid4())}


def png(seed=0):
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    color = bytes((50 + seed % 100, 90 + seed % 120, 140 + seed % 100))
    width, height = 240, 336
    pixels = b''.join(b'\0' + color * width for _ in range(height))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


def upload(path, files, company=None):
    guard()
    boundary = 'NxrLocalFixture' + secrets.token_hex(12)
    parts = []
    for index, (field, filename) in enumerate(files):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\nContent-Type: image/png\r\n\r\n'.encode() + png(index) + b'\r\n')
    parts.append(f'--{boundary}--\r\n'.encode())
    headers = {'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Authorization': 'Bearer ' + TOKEN}
    if company is not None:
        headers['X-NXR-Agent-Id'] = str(company)
    request = urllib.request.Request(BASE + path, data=b''.join(parts), headers=headers)
    try:
        with HTTP.open(request, timeout=40) as response:
            data = json.load(response)
            assert data.get('code', 200) == 200, 'Image import was rejected'
    except urllib.error.HTTPError as response:
        info = json.load(response)
        raise AssertionError(f'Image import failed: HTTP {response.code}, {info.get("message",info.get("msg",""))}') from None
    return data.get('data', data)


def choose(options, preferred):
    return next((x['value'] for x in options if x['value'].lower() == preferred.lower()), options[0]['value'])


def create_order(label, count, company=COMPANY):
    options = owner('/workbench-order-options', company=company)
    cards = []
    for index in range(count):
        front = upload('/api/admin/agent/order-photos', [('file', f'fixture-{label}-{index}-front.png')], company)
        back = upload('/api/admin/agent/order-photos', [('file', f'fixture-{label}-{index}-back.png')], company)
        cards.append({'cardName': f'{PREFIX} {label} card {index + 1}', 'brandName': choose(options['brands'], 'Pokemon'),
                      'setName': 'Local Synthetic Set', 'officialCardNumber': str(index + 1),
                      'languageCode': choose(options['languages'], 'EN'), 'productType': choose(options['productTypes'], 'graded_card'),
                      'category': choose(options['categories'], 'trading_card'), 'frontPhotoId': front['id'], 'backPhotoId': back['id']})
    payload = keyed({'clientName': f'{PREFIX} {label}', 'phone': '15550108888', 'email': f'fixture-{secrets.token_hex(4)}@example.invalid',
                     'notes': 'Synthetic local review: ' + label, 'cards': cards})
    created = owner('/workbench-orders', payload, company=company)
    check('Required photos preserved for ' + label, all(card.get('frontPhotoId') and card.get('backPhotoId') for card in created['cards']))
    check('Short NXR Order number for ' + label, created['order']['orderNo'].startswith('NXR-'))
    check('Order creation retry is idempotent for ' + label, owner('/workbench-orders', payload, company=company)['order']['id'] == created['order']['id'])
    created['_company'] = company
    created['_label'] = label
    REPORT['orders'].append({'label': label, 'companyId': company, 'id': created['order']['id'], 'orderReference': created['order']['orderNo']})
    save()
    return created


def create_address(company):
    return owner('/addresses', {'label': PREFIX, 'contactName': 'Local Fixture Partner', 'contactPhone': '15550109999',
                               'addressLine1': '10 Synthetic Test Road', 'addressLine2': '', 'city': 'Test City', 'region': 'CA',
                               'postalCode': '90002', 'country': 'US', 'defaultAddress': False}, company=company)


def submit(orders, address, company=COMPANY):
    options = owner('/shipping-options?country=US&currencyCode=USD', company=company)
    payload = keyed({'intakeIds': [o['order']['id'] for o in orders], 'returnAddressId': address['id'],
                     'returnShippingOptionCode': options[0]['optionCode'], 'currencyCode': 'USD', 'batchName': PREFIX + ' ' + orders[0]['_label']})
    created = owner('/submissions', payload, company=company)
    batch = owner('/merchant/batches/' + created['batchNo'], company=company)
    REPORT['batches'].append({'id': created['batchId'], 'batchNo': created['batchNo'], 'companyId': company})
    for entry in orders:
        refreshed = owner('/workbench-orders/' + str(entry['order']['id']), company=company)
        entry.update(refreshed)
        for recorded in REPORT['orders']:
            if recorded['id'] == entry['order']['id'] and recorded['companyId'] == company:
                recorded.update({'gradingOrderId': entry['order']['gradingOrderId'], 'batchId': created['batchId']})
    save()
    return batch


def receive_batch(batch, orders, company=COMPANY):
    # The existing approval, terms and confirmed receipt gates remain real API steps.
    for entry in orders:
        order_id, order_no = entry['order']['gradingOrderId'], entry['order']['gradingOrderNo']
        admission = admin(f'/api/admin/order-admissions/{order_id}')
        accepted = admin(f'/api/admin/order-admissions/{order_id}/decision', {'decision': 'approve', 'expectedRevision': admission['admissionRevision'], 'note': PREFIX + ' simulated review'})
        order = owner('/orders/' + order_no, company=company)
        owner(f'/orders/{order_no}/admission/accept-terms', {'termsVersion': accepted['termsVersion'], 'acceptedQuotedAmount': order['totalAmount'], 'acceptedCurrency': order['currencyCode']}, company=company)
        detail = admin(f'/api/admin/orders/{order_id}')
        payment = next(p for p in detail['payments'] if p['statusCode'] == 'pending')
        admin(f'/api/admin/orders/{order_id}/payments/{payment["id"]}/confirm', {'providerTransactionId': 'LOCAL-SYNTHETIC-' + str(uuid.uuid4()), 'note': 'Simulated local receipt; no external payment'})
    inbound = owner(f'/merchant/batches/{batch["batchNo"]}/inbound-shipment', {'carrierName': 'Local Test Carrier', 'trackingNumber': 'LOCAL-IN-' + secrets.token_hex(6)}, company=company)
    shipment = next(s for s in inbound['shipments'] if s['directionCode'] == 'inbound')
    admin(f'/api/admin/merchant-batches/{batch["id"]}/shipments/{shipment["id"]}/delivered', {})
    for entry in orders:
        order_id = entry['order']['gradingOrderId']
        path = f'/api/admin/orders/{order_id}'
        detail = admin(path)
        bench = admin(path + '/workbench/start', {})
        entry['_session'] = bench['activeSession']['id']
        entry['_items'] = bench['items']
        for item in bench['items']:
            admin(path + '/workbench/scan', {'sessionId': entry['_session'], 'stage': 'intake', 'barcode': item['barcode']})
        admin(path + '/intake/receive', {'intakeCode': detail['intakeCode'], 'packageNo': 'LOCAL-PACKET-' + str(order_id), 'receivedCount': detail['totalCardCount'], 'conditionNote': 'Synthetic local cards counted', 'exceptionTypes': []})
    check('Received Submit ' + batch['batchNo'], True)


def grade(entry, index):
    item = entry['_items'][index]
    order_id, item_id = entry['order']['gradingOrderId'], item['orderItemId']
    path = f'/api/admin/orders/{order_id}'
    cert = str(9100000000 + secrets.randbelow(800000000))
    payload = {'certId': cert, 'productType': 'graded_card', 'cardCategory': 'trading_card', 'cardName': item['cardName'], 'yearLabel': '2026',
               'brandName': entry['cards'][index]['brandName'], 'setName': 'Local Synthetic Set', 'cardNumber': str(index + 1), 'languageCode': 'EN',
               'centeringScore': '9', 'edgesScore': '9', 'cornersScore': '9', 'surfaceScore': '9', 'entryNotes': PREFIX + '; synthetic local review'}
    result = admin(path + f'/items/{item_id}/submission', payload)
    submission_id = result['submission']['id']
    media = upload(f'/api/admin/media/submissions/{submission_id}/staged', [('image_files', cert + '_A.png'), ('image_files', cert + '_B.png')])
    assert not media.get('invalidNames') and not media.get('missingCertIds'), 'Synthetic image names rejected'
    check('Grading images remain in local storage ' + str(item_id), sql(f"SELECT COUNT(*) FROM submission_media WHERE submission_id={submission_id} AND is_active=1 AND storage_provider_code='local' AND media_stage_code='staged'") == '2')
    admin(f'/api/admin/submissions/{submission_id}/approve', {})
    # Only this card and common required tasks are completed. Sibling card work remains unfinished.
    for task in admin(path + '/operations')['workTasks']:
        if task['taskTypeCode'] in {'preprocess', 'vision', 'manual_review', 'encapsulation'} and task['statusCode'] != 'completed' and task.get('orderItemId') in {None, item_id}:
            admin(path + f'/tasks/{task["id"]}', {'statusCode': 'completed', 'resultSummary': 'Synthetic local physical-card check completed'})
    admin(path + '/workbench/label-export', {'orderItemIds': [item_id], 'reprintReason': 'Local split-return card label'}, binary=True)
    current = next(row for row in admin(path + '/workbench')['items'] if row['orderItemId'] == item_id)
    for stage in ['label', 'packing']:
        admin(path + '/workbench/scan', {'sessionId': entry['_session'], 'stage': stage, 'barcode': current['labelBarcode']})
    checked = admin(f'/api/admin/partner-returns/cards/{item_id}/check', {'certId': cert, 'qualityPassed': True, 'packingPassed': True, 'note': 'Synthetic local final quality and packing check'})
    check('Card independently released ' + str(item_id), checked['eligible'] and checked['checked'])
    item['_cert'] = cert
    return item_id


def counts():
    return sql("SELECT (SELECT COUNT(*) FROM partner_return_parcel),(SELECT COUNT(*) FROM partner_return_item),(SELECT COUNT(*) FROM partner_return_parcel WHERE status_code='delivered')")


def parcel(ids, label, blocked=None):
    payload = keyed({'orderItemIds': ids, 'carrierName': 'Local Test DHL', 'trackingNumber': 'LOCAL-OUT-' + secrets.token_hex(6), 'note': PREFIX + ' ' + label})
    before = counts()
    result = admin('/api/admin/partner-returns', payload, blocked=blocked)
    if blocked:
        check('Rejected parcel leaves no rows: ' + label, counts() == before)
        return None
    REPORT['parcels'].append({'id': result['id'], 'parcelNo': result['parcelNo'], 'label': label, 'cardCount': result['cardCount']})
    check('Parcel retry is idempotent: ' + label, admin('/api/admin/partner-returns', payload)['id'] == result['id'])
    save()
    return result


def delivered(parcel):
    return admin(f'/api/admin/partner-returns/{parcel["id"]}/delivered', {})


def main():
    global TOKEN, B_TOKEN
    guard()
    assert sql('SELECT display_name FROM customer_account WHERE id=22') == 'Local B Test Partner', 'Unexpected demo company'
    REPORT['status'] = 'running'
    save()
    TOKEN = api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    B_TOKEN = api('/login', {'username': 'local_btest_20260917', 'password': os.environ.get('NXR_QA_B_PASSWORD', 'admin123')})['token']
    check('Existing B user is scoped to the intended local partner', api('/api/admin/agent/context', token=B_TOKEN)['company']['id'] == COMPANY)
    owner('/workbench-orders', keyed({'clientName': PREFIX + ' Missing photos', 'phone': '15550108888', 'cards': [{'cardName': 'Missing photos'}]}), blocked={400})
    address = create_address(COMPANY)
    split = create_order('Completed in two returns', 2)
    partial = create_order('Partial return - one card remains', 2)
    combined = create_order('Combined from another Submit', 1)
    processing = create_order('Grading in progress', 1)
    batch_one = submit([split, partial], address)
    batch_two = submit([combined, processing], address)
    receive_batch(batch_one, [split, partial])
    receive_batch(batch_two, [combined, processing])
    first_card = grade(split, 0)
    parcel([split['_items'][1]['orderItemId']], 'unfinished card rejected', blocked={409})
    first = parcel([first_card], 'First half signed for')
    delivered(first)
    split_id = split['order']['gradingOrderId']
    state = owner('/workbench-orders/' + str(split['order']['id']))['order']
    check('First delivery does not finish the two-card Order', state['deliveredCardCount'] == 1 and state['statusCode'] != 'delivered')
    check('SQL confirms first split delivery leaves one active card', sql(f"SELECT COUNT(*) FROM grading_order_item WHERE order_id={split_id} AND status_code='delivered'") == '1')
    parcel([first_card], 'duplicate card rejected', blocked={409})
    second_card = grade(split, 1)
    second = parcel([second_card], 'Second half signed for')
    delivered(second)
    state = owner('/workbench-orders/' + str(split['order']['id']))['order']
    check('Order closes only after every card reaches B', state['statusCode'] == 'delivered' and state['deliveredCardCount'] == 2)
    check('SQL confirms completed split Order', sql(f'SELECT status_code FROM grading_order WHERE id={split_id}') == 'delivered')
    part_card = grade(partial, 0)
    other_submit_card = grade(combined, 0)
    # A new-only rival customer demonstrates cross-tenant rejection without changing any existing binding.
    rival = api('/api/customer/auth/register', {'email': 'local-return-' + secrets.token_hex(6) + '@example.invalid', 'password': 'Local-Rival-' + secrets.token_urlsafe(12), 'displayName': PREFIX + ' Other B'})
    rival_id = rival['customer']['id']
    admin(f'/api/admin/customers/{rival_id}/type', {'accountTypeCode': 'merchant'}, method='PUT')
    rival_address = create_address(rival_id)
    rival_order = create_order('Other B isolation', 1, rival_id)
    rival_batch = submit([rival_order], rival_address, rival_id)
    receive_batch(rival_batch, [rival_order], rival_id)
    rival_card = grade(rival_order, 0)
    parcel([part_card, rival_card], 'cross-partner merge rejected', blocked={409})
    cross = parcel([part_card, other_submit_card], 'Across Submit - in transit')
    check('One parcel combines cards from different original Submits', len({x['batchId'] for x in cross['items']}) == 2)
    check('Partial Order remains open with a second card still processing', owner('/workbench-orders/' + str(partial['order']['id']))['order']['statusCode'] != 'delivered')
    before = counts()
    api('/api/admin/partner-returns', keyed({'orderItemIds': [rival_card], 'carrierName': 'Forbidden', 'trackingNumber': 'Forbidden'}), token=B_TOKEN, blocked={403})
    api(f'/api/admin/partner-returns/{cross["id"]}/delivered', {}, token=B_TOKEN, blocked={403})
    check('B write attempts leave parcel rows unchanged', counts() == before)
    owner('/partner-return-parcels/' + str(cross['id']), company=rival_id, blocked={404})
    part_order_no = partial['order']['gradingOrderNo']
    tracking = owner(f'/merchant/batches/{batch_one["batchNo"]}/orders/{part_order_no}/tracking-token/rotate', {})
    public = api('/api/public/merchant-order-tracking/' + tracking['trackingToken'])
    exposed = [card['certId'] for p in public['returnParcels'] for card in p['cards']]
    check('Private customer link exposes only its own card from the shared parcel', exposed == [partial['_items'][0]['_cert']])
    check('Private customer link hides the other Order', combined['_items'][0]['_cert'] not in json.dumps(public))
    for row in REPORT['orders']:
        detail = owner('/workbench-orders/' + str(row['id']), company=row['companyId'])
        row.update({k: detail['order'].get(k) for k in ['statusCode', 'cardCount', 'gradedCardCount', 'returnShippedCardCount', 'deliveredCardCount']})
        row['cardStates'] = [{'orderItemId': c['orderItemId'], 'statusCode': c.get('fulfillmentStatusCode')} for c in detail['cards']]
    for row in REPORT['parcels']:
        row['statusCode'] = admin('/api/admin/partner-returns/' + str(row['id']))['statusCode']
    REPORT['status'] = 'passed'
    save()
    print(json.dumps({'status': 'passed', 'checks': len(REPORT['checks']), 'fixturePrefix': PREFIX, 'output': str(OUTPUT)}, ensure_ascii=False))


def readback_only():
    """Recheck the existing fixtures after a same-database restart without creating business rows."""
    global TOKEN, B_TOKEN
    saved = json.loads(OUTPUT.read_text())
    assert saved['database'] == DATABASE and saved['baseUrl'] == BASE and saved['status'] == 'passed'
    guard()
    TOKEN = api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    B_TOKEN = api('/login', {'username': 'local_btest_20260917', 'password': os.environ.get('NXR_QA_B_PASSWORD', 'admin123')})['token']
    before = counts()
    observations = []
    for row in saved['orders']:
        # Explicit GUI changes retain the original API evidence and supply the
        # known pre-restart state instead of silently adopting current values.
        expected = {**row, **saved.get('guiFixtureOverrides', {}).get(row['orderReference'], {})}
        detail = owner('/workbench-orders/' + str(row['id']), company=row['companyId'])['order']
        for field in ['statusCode', 'cardCount', 'gradedCardCount', 'returnShippedCardCount', 'deliveredCardCount']:
            assert detail[field] == expected[field], f'{row["orderReference"]} changed: {field}'
        order_id = row['gradingOrderId']
        assert sql(f'SELECT status_code FROM grading_order WHERE id={order_id}') == expected['statusCode']
        search = admin('/api/admin/orders?query=' + urllib.parse.quote(row['orderReference']))
        matches = [item for item in search['items'] if item['id'] == order_id]
        assert len(matches) == 1 and matches[0]['orderReference'] == row['orderReference'], 'Short Order search mismatch'
        lookup = admin('/api/admin/orders/intake/lookup?intakeCode=' + urllib.parse.quote(row['orderReference'].lower()))
        assert lookup['orderId'] == order_id and lookup['intakeCode'], 'Short Order intake lookup mismatch'
        legacy = admin('/api/admin/orders/intake/lookup?intakeCode=' + urllib.parse.quote(lookup['intakeCode']))
        assert legacy['orderId'] == order_id, 'Original intake-code compatibility mismatch'
        admin_detail = admin(f'/api/admin/orders/{order_id}')
        assert admin_detail['merchantBatch']['clientReference'] == row['orderReference']
        if row['companyId'] == COMPANY:
            own = api('/api/admin/agent/workbench-orders/' + str(row['id']), token=B_TOKEN)
            assert own['order']['gradingOrderId'] == order_id
        observations.append({'orderReference': row['orderReference'], 'gradingOrderId': order_id, 'statusCode': expected['statusCode'], 'shortReferenceLookup': 'passed'})
    for row in saved['parcels']:
        current = admin('/api/admin/partner-returns/' + str(row['id']))
        assert current['statusCode'] == row['statusCode'] and current['cardCount'] == row['cardCount']
        own = api('/api/admin/agent/partner-return-parcels/' + str(row['id']), token=B_TOKEN)
        assert own['id'] == current['id'] and own['statusCode'] == current['statusCode']
    for row in saved.get('guiParcels', []):
        current = admin('/api/admin/partner-returns/' + str(row['id']))
        assert current['statusCode'] == row['statusCode'] and current['cardCount'] == row['cardCount']
        assert current['companyId'] == row['companyId']
        assert sorted({item['orderId'] for item in current['items']}) == row['orderIds']
        if row['companyId'] != COMPANY:
            api('/api/admin/agent/partner-return-parcels/' + str(row['id']), token=B_TOKEN, blocked={404})
    assert counts() == before, 'Readback changed parcel records'
    saved['postRestartReadback'] = {'status': 'passed', 'checkedAt': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'orders': observations, 'parcelCount': len(saved['parcels']) + len(saved.get('guiParcels', [])), 'businessWrites': 0}
    OUTPUT.write_text(json.dumps(saved,ensure_ascii=False,indent=2))
    OUTPUT.chmod(0o600)
    print(json.dumps({'readback': 'passed', 'orders': len(observations), 'parcels': saved['postRestartReadback']['parcelCount'], 'businessWrites': 0, 'output': str(OUTPUT)}))


if __name__ == '__main__':
    if '--readback-only' in sys.argv[1:]:
        readback_only()
    else:
        try:
            main()
        except Exception as error:
            REPORT['status'] = 'failed'
            REPORT['failure'] = str(error)
            save()
            raise
