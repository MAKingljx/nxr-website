#!/usr/bin/env python3
"""Real localhost admission and return-gate checks using new synthetic Orders.

Reuses the existing guarded verifier's API and image helpers. No SQL mutation,
account mutation, production endpoint, real payment or remote storage is used.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import uuid

SPEC = importlib.util.spec_from_file_location('partner_return_helpers', Path(__file__).with_name('verify-partner-order-return.py'))
h = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(h)
RUN = '20261015-' + secrets.token_hex(5)
h.PREFIX = 'Local Exception ' + RUN
h.OUTPUT = Path('/tmp/nxr-partner-order-exceptions-' + RUN + '.json')
h.REPORT = {'database': h.DATABASE, 'baseUrl': h.BASE, 'fixturePrefix': h.PREFIX,
            'status': 'preparing', 'checks': [], 'orders': [], 'batches': [], 'parcels': [],
            'rejections': [], 'coverageLimits': ['GUI is verified separately by the coordinating task.',
              'Actual quote drift and unsettled shipping adjustments use isolated unit contracts because the B API has no quote-edit or shipping-change entry.'],
            'realPayments': 0, 'providerNetworkCalls': 0, 'emailsSent': 0, 'r2Writes': 0}
ORIGINAL_GUARD = h.guard
ORIGINAL_API = h.api
ORIGINAL_UPLOAD = h.upload


def guard():
    ORIGINAL_GUARD()
    runtime = json.loads(Path('/tmp/nxr-local-b-orders-20261014-runtime.json').read_text())
    assert runtime['database'] == h.DATABASE and runtime['backend'] == h.BASE
    assert all(runtime[k] == 'local' for k in ['storageDriver', 'stagingDriver', 'publishedDriver'])
    # Inspect only a fixed allowlist; never emit the process environment.
    env = subprocess.check_output(['ps', 'eww', '-p', str(runtime['backend_pid']), '-o', 'command='], text=True)
    def value(name):
        match = re.search(r'(?:^| )' + re.escape(name) + r'=([^ ]*)', env)
        return None if match is None else match.group(1)
    assert value('NXR_NOTIFICATION_DELIVERY_ENABLED') == 'false'
    assert value('NXR_STORAGE_DRIVER') == 'local'
    assert all((value(k) or value('NXR_STORAGE_DRIVER')) == 'local'
               for k in ['NXR_MEDIA_STAGING_DRIVER', 'NXR_MEDIA_PUBLISHED_DRIVER'])
    db_url = value('NXR_DB_URL') or ''
    assert 'jdbc:mysql://127.0.0.1:3306/' + h.DATABASE + '?' in db_url
    assert 'java ' in env and 'ruoyi-admin.jar' in env


def api(path, payload=None, **kwargs):
    data = ORIGINAL_API(path, payload, **kwargs)
    if kwargs.get('blocked'):
        h.REPORT['rejections'].append({'path': path, 'code': data.get('code', data.get('status')),
                                      'message': data.get('message', data.get('msg'))})
        h.save()
    return data


def owner(path, payload=None, company=h.COMPANY, **kwargs):
    assert company == h.COMPANY, 'Only the existing local B company is used'
    return h.api('/api/admin/agent' + path, payload, token=h.B_TOKEN, **kwargs)


def upload(path, files, company=None):
    if company is None:
        return ORIGINAL_UPLOAD(path, files)
    assert company == h.COMPANY
    saved = h.TOKEN
    try:
        h.TOKEN = h.B_TOKEN
        return ORIGINAL_UPLOAD(path, files)
    finally:
        h.TOKEN = saved


h.guard, h.api, h.owner, h.upload = guard, api, owner, upload


def identity_digest():
    # Existing identity hashes stay in memory, never in stdout or the evidence.
    # Authentication intentionally updates login metadata; compare identity and access columns.
    selects = {'sys_user': 'user_id,dept_id,user_name,nick_name,user_type,email,phonenumber,password,status,del_flag,pwd_update_date',
               'sys_user_role': 'user_id,role_id', 'agent_operator_binding': '*',
               'customer_account': 'id,email,password_hash,display_name,mobile,account_type_code,is_active,email_verified_at'}
    return {table: hashlib.sha256(h.sql('SELECT ' + columns + ' FROM ' + table + ' ORDER BY 1').encode()).hexdigest()
            for table, columns in selects.items()}


def snapshot(entry):
    oid = entry['order']['gradingOrderId']
    bid = entry['_batch']['id']
    return h.sql(f'''SELECT
      (SELECT CONCAT_WS('|',status_code,admission_status_code,admission_revision,COALESCE(accepted_terms_version,'')) FROM grading_order WHERE id={oid}),
      (SELECT COUNT(*) FROM merchant_batch_shipment WHERE batch_id={bid}),
      (SELECT COUNT(*) FROM order_shipment WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_intake_receipt WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_physical_item WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_workbench_session WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_workbench_scan WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_admission_event WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_admission_supplemental_photo WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_terms_acceptance WHERE order_id={oid}),
      (SELECT COUNT(*) FROM order_timeline_event WHERE order_id={oid}),
      (SELECT GROUP_CONCAT(CONCAT_WS(':',id,status_code,attempt_count) ORDER BY id) FROM order_work_task WHERE order_id={oid}),
      (SELECT GROUP_CONCAT(CONCAT_WS(':',id,status_code,COALESCE(grading_submission_id,0)) ORDER BY id) FROM grading_order_item WHERE order_id={oid}),
      (SELECT GROUP_CONCAT(CONCAT_WS(':',id,status_code,amount) ORDER BY id) FROM payment_record WHERE order_id={oid}),
      (SELECT COUNT(*) FROM partner_return_card_check c JOIN grading_order_item i ON i.id=c.order_item_id WHERE i.order_id={oid}),
      (SELECT COUNT(*) FROM partner_return_item r JOIN grading_order_item i ON i.id=r.order_item_id WHERE i.order_id={oid}),
      (SELECT COUNT(*) FROM partner_return_parcel WHERE request_key LIKE '{RUN}%')''')


def denied(entry, label, path, payload, *, b=False, codes=(409,)):
    before = snapshot(entry)
    result = h.api(path, payload, token=h.B_TOKEN if b else h.TOKEN, blocked=set(codes))
    h.check('Zero business changes: ' + label, snapshot(entry) == before)
    h.save()
    return result


def parcel(entry, label, blocked=True):
    payload = {'orderItemIds': [entry['_item']['orderItemId']], 'carrierName': 'Local Synthetic Carrier',
               'trackingNumber': 'LOCAL-EX-' + secrets.token_hex(8), 'requestKey': RUN + '-' + str(uuid.uuid4()),
               'note': h.PREFIX + ' ' + label}
    if blocked:
        return denied(entry, label, '/api/admin/partner-returns', payload)
    result = h.admin('/api/admin/partner-returns', payload)
    h.REPORT['parcels'].append({'id': result['id'], 'parcelNo': result['parcelNo'], 'label': label})
    h.check('Successful recovery parcel is idempotent', h.admin('/api/admin/partner-returns', payload)['id'] == result['id'])
    return result


def create(label, address):
    entry = h.create_order(label, 1)
    entry['_batch'] = h.submit([entry], address)
    entry['_path'] = '/api/admin/orders/' + str(entry['order']['gradingOrderId'])
    entry['_bpath'] = '/api/admin/agent/orders/' + entry['order']['gradingOrderNo']
    entry['_item'] = {'orderItemId': entry['cards'][0]['orderItemId']}
    return entry


def decision(entry, kind, revision=None):
    oid = entry['order']['gradingOrderId']
    admission = h.admin(f'/api/admin/order-admissions/{oid}')
    return h.admin(f'/api/admin/order-admissions/{oid}/decision', {'decision': kind,
                   'expectedRevision': admission['admissionRevision'] if revision is None else revision,
                   'note': h.PREFIX + ' ' + kind})


def accept(entry, approved):
    order = h.owner('/orders/' + entry['order']['gradingOrderNo'])
    return h.owner('/orders/' + entry['order']['gradingOrderNo'] + '/admission/accept-terms',
                   {'termsVersion': approved['termsVersion'], 'acceptedQuotedAmount': order['totalAmount'], 'acceptedCurrency': order['currencyCode']})


def receive(entry):
    path = entry['_path']
    detail = h.admin(path)
    payment = next(p for p in detail['payments'] if p['statusCode'] == 'pending')
    h.admin(path + f'/payments/{payment["id"]}/confirm', {'providerTransactionId': 'LOCAL-EXCEPTION-' + str(uuid.uuid4()),
             'note': 'Synthetic receipt only, no external payment'})
    batch = entry['_batch']
    inbound = h.owner(f'/merchant/batches/{batch["batchNo"]}/inbound-shipment',
                      {'carrierName': 'Local Test Carrier', 'trackingNumber': 'LOCAL-IN-' + secrets.token_hex(8)})
    shipment = next(s for s in inbound['shipments'] if s['directionCode'] == 'inbound')
    h.admin(f'/api/admin/merchant-batches/{batch["id"]}/shipments/{shipment["id"]}/delivered', {})
    bench = h.admin(path + '/workbench/start', {})
    entry['_session'] = bench['activeSession']['id']
    entry['_item'] = bench['items'][0]
    h.admin(path + '/workbench/scan', {'sessionId': entry['_session'], 'stage': 'intake', 'barcode': entry['_item']['barcode']})
    detail = h.admin(path)
    h.admin(path + '/intake/receive', {'intakeCode': detail['intakeCode'], 'packageNo': 'LOCAL-EX-' + str(detail['id']),
             'receivedCount': 1, 'conditionNote': 'Synthetic local card counted', 'exceptionTypes': []})
    h.check('Received new synthetic Order ' + str(detail['id']), h.sql(f"SELECT status_code FROM grading_order WHERE id={detail['id']}") == 'received')


def grading(entry):
    item = entry['_item']
    cert = str(9500000000 + secrets.randbelow(400000000))
    payload = {'certId': cert, 'productType': 'graded_card', 'cardCategory': 'trading_card', 'cardName': item['cardName'],
               'yearLabel': '2026', 'brandName': entry['cards'][0]['brandName'], 'setName': 'Local Synthetic Set',
               'cardNumber': '1', 'languageCode': 'EN', 'centeringScore': '9', 'edgesScore': '9',
               'cornersScore': '9', 'surfaceScore': '9', 'entryNotes': h.PREFIX + ' synthetic pending result'}
    result = h.admin(entry['_path'] + f'/items/{item["orderItemId"]}/submission', payload)
    sid = result['submission']['id']
    entry['_submission'], entry['_cert'] = sid, cert
    h.upload(f'/api/admin/media/submissions/{sid}/staged', [('image_files', cert + '_A.png'), ('image_files', cert + '_B.png')])
    h.check('Grading images use local storage only', h.sql(f"SELECT COUNT(*) FROM submission_media WHERE submission_id={sid} AND is_active=1 AND storage_provider_code='local'") == '2')
    parcel(entry, 'Pending grading cannot return')
    denied(entry, 'Pending grading cannot pass card check', f'/api/admin/partner-returns/cards/{item["orderItemId"]}/check',
           {'certId': cert, 'qualityPassed': True, 'packingPassed': True})
    h.admin(f'/api/admin/submissions/{sid}/approve', {})
    for task in h.admin(entry['_path'] + '/operations')['workTasks']:
        if task['taskTypeCode'] in {'preprocess', 'vision', 'manual_review', 'encapsulation'} and task['statusCode'] != 'completed':
            h.admin(entry['_path'] + f'/tasks/{task["id"]}', {'statusCode': 'completed', 'resultSummary': 'Synthetic local physical-card check'})


def labels(entry):
    h.admin(entry['_path'] + '/workbench/label-export', {'orderItemIds': [entry['_item']['orderItemId']],
             'reprintReason': 'Synthetic local exception acceptance'}, binary=True)
    current = h.admin(entry['_path'] + '/workbench')['items'][0]
    for stage in ['label', 'packing']:
        h.admin(entry['_path'] + '/workbench/scan', {'sessionId': entry['_session'], 'stage': stage, 'barcode': current['labelBarcode']})
    result = h.admin(f'/api/admin/partner-returns/cards/{current["orderItemId"]}/check',
                     {'certId': entry['_cert'], 'qualityPassed': True, 'packingPassed': True, 'note': h.PREFIX})
    h.check('Current label and physical checks allow return', result['eligible'] and result['checked'])
    return current['labelBarcode']


def main():
    guard()
    baseline = identity_digest()
    h.TOKEN = h.api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    h.B_TOKEN = h.api('/login', {'username': 'local_btest_20260917', 'password': os.environ.get('NXR_QA_B_PASSWORD', 'admin123')})['token']
    h.check('Existing B identity is company 22 without platform privileges', h.api('/api/admin/agent/context', token=h.B_TOKEN)['company']['id'] == 22)
    address = next(x for x in h.owner('/addresses') if x['id'] == 8)
    h.REPORT['status'] = 'running'
    h.save()
    info = create('Supplement and recover', address)
    requested = decision(info, 'request_information')
    denied(info, 'Information-needed Order cannot bypass review', f'/api/admin/order-admissions/{info["order"]["gradingOrderId"]}/decision',
           {'decision': 'approve', 'expectedRevision': requested['admissionRevision'], 'note': h.PREFIX})
    denied(info, 'Empty supplement rejected', info['_bpath'] + '/admission/resubmit', {'note': '', 'supplementalPhotoIds': []}, b=True, codes=(400,))
    supplement = h.upload('/api/admin/agent/order-photos', [('file', 'synthetic-supplement.png')], h.COMPANY)
    resubmitted = h.owner('/orders/' + info['order']['gradingOrderNo'] + '/admission/resubmit',
                          {'note': h.PREFIX + ' clearer set and card details', 'supplementalPhotoIds': [supplement['id']]})
    h.check('B supplement returns admission to pending review', resubmitted['admissionStatus'] == 'pending_review')
    oid = info['order']['gradingOrderId']
    h.check('SQL retains supplementary photo attachment', h.sql(f'SELECT COUNT(*) FROM order_admission_supplemental_photo WHERE order_id={oid} AND photo_id={supplement["id"]}') == '1')
    denied(info, 'Old reviewer revision rejected after supplement', f'/api/admin/order-admissions/{oid}/decision',
           {'decision': 'approve', 'expectedRevision': requested['admissionRevision'], 'note': h.PREFIX})
    approved = decision(info, 'approve')
    order = h.owner('/orders/' + info['order']['gradingOrderNo'])
    denied(info, 'Old terms version rejected', info['_bpath'] + '/admission/accept-terms',
           {'termsVersion': 'OLD-SYNTHETIC-VERSION', 'acceptedQuotedAmount': order['totalAmount'], 'acceptedCurrency': order['currencyCode']}, b=True)
    denied(info, 'Stale presented quote rejected', info['_bpath'] + '/admission/accept-terms',
           {'termsVersion': approved['termsVersion'], 'acceptedQuotedAmount': order['totalAmount'] + 1, 'acceptedCurrency': order['currencyCode']}, b=True)
    accept(info, approved)
    denied(info, 'Unpaid Order cannot ship inbound', f'/api/admin/agent/merchant/batches/{info["_batch"]["batchNo"]}/inbound-shipment',
           {'carrierName': 'Local Carrier', 'trackingNumber': 'LOCAL-UNPAID'}, b=True)
    denied(info, 'Unpaid Order cannot be received', info['_path'] + '/intake/receive',
           {'intakeCode': order['intakeCode'], 'receivedCount': 1, 'conditionNote': 'Synthetic', 'exceptionTypes': []})
    parcel(info, 'Unpaid Order cannot return')
    receive(info)
    grading(info)
    old_code = labels(info)
    tasks = h.admin(info['_path'] + '/tasks', {'orderItemId': info['_item']['orderItemId'], 'taskTypeCode': 'manual_review'})
    rework = max((task for task in tasks['workTasks'] if task['taskTypeCode'] == 'manual_review'
                  and task.get('orderItemId') == info['_item']['orderItemId']), key=lambda task: task['id'])
    h.admin(info['_path'] + f'/tasks/{rework["id"]}', {'statusCode': 'failed', 'failureReason': h.PREFIX + ' synthetic grading rework'})
    parcel(info, 'Failed grading rework task cannot return')
    h.admin(info['_path'] + f'/tasks/{rework["id"]}', {'statusCode': 'completed', 'resultSummary': h.PREFIX + ' rework completed'})
    checked = h.admin(f'/api/admin/partner-returns/cards/{info["_item"]["orderItemId"]}/check',
                     {'certId': info['_cert'], 'qualityPassed': True, 'packingPassed': True, 'note': h.PREFIX})
    h.check('Completed grading rework restores release eligibility', checked['eligible'])
    h.admin(info['_path'] + '/workbench/label-export', {'orderItemIds': [info['_item']['orderItemId']], 'reprintReason': 'Damaged synthetic label reprint'}, binary=True)
    denied(info, 'Old printed label cannot scan after reprint', info['_path'] + '/workbench/scan',
           {'sessionId': info['_session'], 'stage': 'label', 'barcode': old_code})
    parcel(info, 'Reprinted label requires current scans')
    labels(info)
    h.admin(info['_path'] + '/status', {'statusCode': 'review', 'detail': h.PREFIX + ' review'})
    h.admin(info['_path'] + '/quality-check', {'passed': False, 'note': h.PREFIX + ' synthetic encapsulation rework'})
    h.check('Failed quality check records a rework hold', h.sql(f'SELECT status_code FROM grading_order WHERE id={oid}') == 'quality_hold')
    parcel(info, 'Quality rework hold cannot return')
    h.admin(info['_path'] + '/quality-check', {'passed': True, 'note': h.PREFIX + ' rework completed and verified'})
    labels(info)
    recovered = parcel(info, 'Recovered Order returns successfully', blocked=False)
    h.admin(f'/api/admin/partner-returns/{recovered["id"]}/delivered', {})
    h.check('Recovered Order closes after delivery', h.owner('/workbench-orders/' + str(info['order']['id']))['order']['statusCode'] == 'delivered')
    rejected = create('Rejected admission', address)
    decision(rejected, 'reject')
    denied(rejected, 'Rejected Order cannot resubmit', rejected['_bpath'] + '/admission/resubmit', {'note': h.PREFIX}, b=True)
    denied(rejected, 'Rejected Order cannot ship inbound', f'/api/admin/agent/merchant/batches/{rejected["_batch"]["batchNo"]}/inbound-shipment',
           {'carrierName': 'Local Carrier', 'trackingNumber': 'LOCAL-REJECTED'}, b=True)
    rejected_order = h.admin(rejected['_path'])
    denied(rejected, 'Rejected Order cannot be received', rejected['_path'] + '/intake/receive',
           {'intakeCode': rejected_order['intakeCode'], 'receivedCount': 1, 'conditionNote': 'Synthetic', 'exceptionTypes': []})
    denied(rejected, 'Rejected Order cannot bypass generic status', rejected['_path'] + '/status', {'statusCode': 'received', 'detail': h.PREFIX})
    parcel(rejected, 'Rejected Order cannot return')
    for row in h.REPORT['orders']:
        current = h.owner('/workbench-orders/' + str(row['id']))['order']
        row['finalStatusCode'] = current['statusCode']
        row['admissionStatus'] = h.admin(f'/api/admin/order-admissions/{row["gradingOrderId"]}')['admissionStatus']
    h.check('Existing users, passwords, roles, B binding and customer accounts unchanged', identity_digest() == baseline)
    h.REPORT['status'] = 'passed'
    h.save()
    print(json.dumps({'status': 'passed', 'checks': len(h.REPORT['checks']), 'rejections': len(h.REPORT['rejections']), 'output': str(h.OUTPUT)}, ensure_ascii=False))


def readback_only(source):
    """Verify recorded fixtures after the coordinator restarts the same database."""
    source = Path(source).resolve()
    assert source.parent == Path('/tmp').resolve() and source.name.startswith('nxr-partner-order-exceptions-')
    saved = json.loads(source.read_text())
    assert saved['database'] == h.DATABASE and saved['baseUrl'] == h.BASE and saved['status'] == 'passed'
    assert saved['fixturePrefix'].startswith('Local Exception ')
    guard()
    identities = identity_digest()
    h.TOKEN = h.api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    h.B_TOKEN = h.api('/login', {'username': 'local_btest_20260917', 'password': os.environ.get('NXR_QA_B_PASSWORD', 'admin123')})['token']
    h.REPORT.update(status='readback_running', sourceEvidence=str(source), businessWrites=0)
    observations = []
    for row in saved['orders']:
        detail = h.owner('/workbench-orders/' + str(row['id']))['order']
        h.check('Recorded Order ownership and state survive restart: ' + row['orderReference'],
                detail['gradingOrderId'] == row['gradingOrderId'] and detail['statusCode'] == row['finalStatusCode'])
        admission = h.admin('/api/admin/order-admissions/' + str(row['gradingOrderId']))
        h.check('Recorded admission survives restart: ' + row['orderReference'], admission['admissionStatus'] == row['admissionStatus'])
        h.check('SQL state agrees after restart: ' + row['orderReference'],
                h.sql(f'SELECT status_code FROM grading_order WHERE id={row["gradingOrderId"]}') == row['finalStatusCode'])
        observations.append({'orderReference': row['orderReference'], 'gradingOrderId': row['gradingOrderId'], 'statusCode': detail['statusCode']})
    for row in saved['parcels']:
        detail = h.admin('/api/admin/partner-returns/' + str(row['id']))
        own = h.owner('/partner-return-parcels/' + str(row['id']))
        h.check('Recovered parcel remains delivered after restart', detail['statusCode'] == own['statusCode'] == 'delivered')
    h.check('Readback preserves existing identity and access', identity_digest() == identities)
    h.REPORT.update(status='readback_passed', observations=observations)
    h.save()
    print(json.dumps({'status': 'readback_passed', 'checks': len(h.REPORT['checks']), 'businessWrites': 0, 'output': str(h.OUTPUT)}))


if __name__ == '__main__':
    try:
        if len(sys.argv) == 3 and sys.argv[1] == '--readback-only':
            readback_only(sys.argv[2])
        else:
            assert len(sys.argv) == 1, 'Use no arguments, or --readback-only /tmp/<evidence>.json'
            main()
    except Exception as exc:
        h.REPORT.update(status='failed', failure=str(exc))
        h.save()
        print(str(h.OUTPUT), file=sys.stderr)
        raise
