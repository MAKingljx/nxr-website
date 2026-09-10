#!/usr/bin/env python3
"""Second-stage acceptance for aggregate batches, scoped staff, and order workbench.

Run only against the dedicated ``nxr_acceptance_iteration_20260908_a`` database
and the loopback QA service on port 8090. The first-stage script must already
have produced ``/tmp/nxr-iteration-qa-session.json``. This script never enables
mail or payment providers and never prints passwords or session/tracking tokens.
Fixtures are intentionally retained for inspection and are removed by dropping
the disposable QA database.
"""
from copy import deepcopy
from decimal import Decimal
import bcrypt
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import urllib.error
import urllib.request


FIRST_STAGE = Path(__file__).with_name('verify-order-iteration.py')
spec = importlib.util.spec_from_file_location('order_iteration_qa', FIRST_STAGE)
iteration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(iteration)

# Reuse the first-stage harness and its no-proxy loopback client.
qa, api, check, sql, raw = iteration.qa, iteration.api, iteration.check, iteration.sql, iteration.raw
EXPECTED_DATABASE = 'nxr_acceptance_iteration_20260908_a'
SESSION_PATH = Path('/tmp/nxr-iteration-qa-session.json')
RESULT_PATH = Path('/tmp/nxr-order-operations-results.json')


def decimal(value):
    return Decimal(str(value))


def json_keys(value):
    if isinstance(value, dict):
        return set(value).union(*(json_keys(item) for item in value.values()))
    if isinstance(value, list):
        return set().union(*(json_keys(item) for item in value)) if value else set()
    return set()


def scalar(statement):
    value = sql(statement)
    return int(value) if value else 0


def fixture_counts():
    tables = (
        'merchant_order_batch', 'merchant_order_batch_item',
        'merchant_batch_tracking_token', 'grading_order', 'grading_order_item',
    )
    return tuple(scalar(f'SELECT COUNT(*) FROM {table}') for table in tables)


def secret_api(path, label, token=None, method='GET', expected_status=200, payload=None):
    """Call a URL containing a private token without putting that URL in checks/errors."""
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['X-NXR-Customer-Token'] = token
    request = urllib.request.Request(
        qa.BASE + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers=headers,
        method=method,
    )
    try:
        with qa.http.open(request, timeout=30) as response:
            status = response.status
            body = json.load(response)
    except urllib.error.HTTPError as response:
        status = response.code
        body = None
    check(label, status == expected_status)
    return body


def binary_post(path, token, payload):
    request = urllib.request.Request(
        qa.BASE + path,
        data=json.dumps(payload).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'},
        method='POST',
    )
    with qa.http.open(request, timeout=30) as response:
        return response.read(), response.headers


def order_payload(base, names, shipping_code, photo_id=None, address_line=None):
    payload = deepcopy(base)
    payload.pop('quotedTotalAmount', None)
    payload.pop('quotedCurrencyCode', None)
    payload['returnShippingOptionCode'] = shipping_code
    payload['languageGroups'] = []
    if address_line is not None:
        payload['returnAddressLine1'] = address_line
    template = deepcopy(base['items'][0])
    payload['items'] = []
    for index, name in enumerate(names):
        item = {**template, 'cardName': name, 'cardNumber': f'QA-{index + 1}'}
        if photo_id is not None and index == 0:
            item['frontPhotoId'] = photo_id
        payload['items'].append(item)
    return payload


def batch_row(reference, order, display_name):
    return {
        'clientReference': reference,
        'clientDisplayName': display_name,
        'clientContactHint': 'private local QA fixture',
        'order': order,
    }


def submission_payload(cert_id, label):
    return {
        'certId': cert_id,
        'productType': 'graded_card',
        'cardCategory': 'trading_card',
        'cardName': f'{label} Card',
        'yearLabel': '2026',
        'brandName': 'NXR QA',
        'setName': 'Order Operations',
        'cardNumber': cert_id[-4:],
        'languageCode': 'EN',
        'centeringScore': '9.0',
        'edgesScore': '9.0',
        'cornersScore': '9.0',
        'surfaceScore': '9.0',
        'entryNotes': 'Disposable isolated acceptance fixture',
    }


def create_qa_shipping_policy(admin, suffix):
    return admin('/api/admin/commerce-policy/shipping-policy', {
        'policyCode': 'qa_batch_weight_' + suffix,
        'displayName': 'QA aggregate return parcel ' + suffix,
        'destinationCountry': 'US',
        'currencyCode': 'USD',
        'perCardWeightGrams': 20,
        'packagingWeightGrams': 40,
        'firstWeightGrams': 100,
        'firstWeightPrice': '4.00',
        'additionalWeightGrams': 100,
        'additionalWeightPrice': '2.00',
        'discountQuantityThreshold': None,
        'discountPercent': '0.00',
        'freeShippingQuantityThreshold': None,
        'priorityNo': 999,
        'active': True,
    }, method='PUT')


def verify_batch_logistics(
    session, suffix, admin, merchant, scoped, scoped_token, shipping_code, cert_seed, unrelated_order_id
):
    """Exercise master-parcel gates while every child follows the real order workflow."""
    legacy_individual = sql(
        "SELECT s.order_id,s.id FROM order_shipment s "
        "JOIN merchant_order_batch_item bi ON bi.order_id=s.order_id "
        "WHERE s.tracking_number LIKE 'QA-INDIVIDUAL-IN-%' AND s.status_code<>'delivered' "
        "ORDER BY s.id DESC LIMIT 1"
    )
    if legacy_individual:
        legacy_order_id, legacy_shipment_id = (int(value) for value in legacy_individual.split('\t'))
        admin(
            f'/api/admin/orders/{legacy_order_id}/shipments/{legacy_shipment_id}/delivered',
            {}, blocked={409},
        )
        admin(
            f'/api/admin/orders/{legacy_order_id}/shipments/{legacy_shipment_id}/tracking',
            {
                'eventCode': 'in_transit',
                'eventTitle': 'Forbidden individual batch tracking',
                'locationLabel': 'QA only',
                'eventDetail': 'Must use the master parcel timeline',
                'eventTime': None,
            }, blocked={409},
        )
        check('an existing individual batch-child shipment cannot bypass the delivery guard', True)

    rows = [
        batch_row(
            'LOG-A', order_payload(session['baseOrder'], ['Logistics Child A'], shipping_code), 'Logistics A'
        ),
        batch_row(
            'LOG-B', order_payload(session['baseOrder'], ['Logistics Child B'], shipping_code), 'Logistics B'
        ),
    ]
    batch = merchant('/api/customer/merchant/batches', {
        'sourceName': 'qa-master-logistics-' + suffix,
        'batchName': 'QA master logistics ' + suffix,
        'orders': rows,
    })
    batch_id = int(batch['batchId'])
    batch_no = batch['batchNo']
    child_rows = batch['rows']
    child_ids = [int(row['orderId']) for row in child_rows]
    inbound_payload = {
        'carrierName': 'QA Master Inbound',
        'trackingNumber': 'QA-MASTER-IN-' + suffix,
        'note': 'Disposable isolated master parcel',
    }
    merchant(f'/api/customer/merchant/batches/{batch_no}/inbound-shipment', inbound_payload, blocked={409})
    check(
        'unpaid batch cannot create a shared inbound parcel',
        scalar(f"SELECT COUNT(*) FROM merchant_batch_shipment WHERE batch_id={batch_id} AND direction_code='inbound'") == 0,
    )

    def approve_and_pay(child):
        order_id = int(child['orderId'])
        order_no = child['orderNo']
        admission = admin(f'/api/admin/order-admissions/{order_id}')
        approved = admin(f'/api/admin/order-admissions/{order_id}/decision', {
            'decision': 'approve',
            'note': 'QA master parcel admission',
            'expectedRevision': admission['admissionRevision'],
        })
        detail = merchant(f'/api/customer/orders/{order_no}')
        merchant(f'/api/customer/orders/{order_no}/admission/accept-terms', {
            'termsVersion': approved['termsVersion'],
            'acceptedQuotedAmount': detail['totalAmount'],
            'acceptedCurrency': detail['currencyCode'],
        })
        return merchant(f'/api/customer/orders/{order_no}/wallet-payment', {
            'idempotencyKey': 'qa-master-pay-' + suffix + '-' + str(order_id),
        })

    first_paid = approve_and_pay(child_rows[0])
    check('first master-parcel child reaches paid inbound readiness', first_paid['statusCode'] == 'awaiting_inbound')
    merchant(f'/api/customer/merchant/batches/{batch_no}/inbound-shipment', inbound_payload, blocked={409})
    check(
        'one unpaid child still blocks shared inbound creation without partial shipment',
        scalar(f"SELECT COUNT(*) FROM merchant_batch_shipment WHERE batch_id={batch_id} AND direction_code='inbound'") == 0,
    )
    second_paid = approve_and_pay(child_rows[1])
    check('second master-parcel child reaches paid inbound readiness', second_paid['statusCode'] == 'awaiting_inbound')
    merchant(f'/api/customer/orders/{child_rows[0]["orderNo"]}/inbound-shipment', {
        'direction': 'inbound',
        'carrierName': 'Forbidden Individual Inbound',
        'trackingNumber': 'QA-INDIVIDUAL-IN-' + suffix,
        'note': 'Must use the master parcel',
    }, blocked={409})
    inbound = merchant(f'/api/customer/merchant/batches/{batch_no}/inbound-shipment', inbound_payload)
    check(
        'all paid children enter one shared inbound parcel together',
        inbound['statusCode'] == 'inbound_shipped'
        and all(row['statusCode'] == 'inbound_shipped' for row in inbound['orders'])
        and len([row for row in inbound['shipments'] if row['directionCode'] == 'inbound']) == 1,
    )
    inbound_shipment = next(row for row in inbound['shipments'] if row['directionCode'] == 'inbound')
    received_batch = admin(
        f'/api/admin/merchant-batches/{batch_id}/shipments/{int(inbound_shipment["id"])}/delivered', {}
    )
    check('master inbound delivery advances the batch receiving state', received_batch['statusCode'] == 'received')

    def complete_child(child, cert_id, pack_now):
        order_id = int(child['orderId'])
        order_path = f'/api/admin/orders/{order_id}'
        detail = scoped(order_path)
        workbench = scoped(order_path + '/workbench/start', {})
        workbench_session_id = int(workbench['activeSession']['id'])
        workbench_item = workbench['items'][0]
        physical_barcode = workbench_item['barcode']
        item_id = int(workbench_item['orderItemId'])
        scoped(order_path + '/workbench/scan', {
            'sessionId': workbench_session_id, 'stage': 'intake', 'barcode': physical_barcode,
        })
        scoped(order_path + '/intake/receive', {
            'intakeCode': detail['intakeCode'],
            'packageNo': 'QA-MASTER-CHILD-' + str(order_id),
            'receivedCount': int(detail['totalCardCount']),
            'conditionNote': 'Master parcel child count matches',
            'exceptionTypes': [],
        })
        created = scoped(order_path + f'/items/{item_id}/submission', submission_payload(cert_id, 'Master Child'))
        submission_id = int(created['submission']['id'])
        scoped(f'/api/admin/submissions/{submission_id}/approve', {})
        operations = scoped(order_path + '/operations')
        for task in operations['workTasks']:
            if task['taskTypeCode'] in {'preprocess', 'vision', 'manual_review', 'encapsulation'} \
                and task['statusCode'] != 'completed':
                scoped(order_path + f'/tasks/{int(task["id"])}', {
                    'statusCode': 'completed',
                    'resultSummary': 'Completed for isolated master parcel QA',
                    'failureReason': None,
                })
        scoped(order_path + '/status', {'statusCode': 'review', 'detail': 'Master parcel grading reviewed'})
        scoped(order_path + '/quality-check', {'passed': True, 'note': 'Master parcel final check passed'})
        state = {
            'orderId': order_id,
            'orderPath': order_path,
            'sessionId': workbench_session_id,
            'physicalBarcode': physical_barcode,
            'certId': cert_id,
            'submissionId': submission_id,
        }
        if pack_now:
            pack_child(state)
        return state

    def pack_child(state):
        labels, _ = binary_post(state['orderPath'] + '/workbench/label-export', scoped_token, {})
        check(
            'master-parcel child label export stays bound to its own grading record',
            state['certId'].encode() in labels,
        )
        snapshot = scoped(state['orderPath'] + '/workbench')
        label_barcode = snapshot['items'][0]['labelBarcode']
        scoped(state['orderPath'] + '/workbench/scan', {
            'sessionId': state['sessionId'], 'stage': 'label', 'barcode': label_barcode,
        })
        scoped(state['orderPath'] + '/workbench/scan', {
            'sessionId': state['sessionId'], 'stage': 'packing', 'barcode': label_barcode,
        })
        scoped(state['orderPath'] + '/workbench/packing-check', {'sessionId': state['sessionId']})

    first_state = complete_child(child_rows[0], str(cert_seed), True)
    individual_outbound = {
        'direction': 'outbound',
        'carrierName': 'Forbidden Individual Return',
        'trackingNumber': 'QA-INDIVIDUAL-OUT-' + suffix,
        'note': 'Must wait for the full master parcel',
    }
    scoped(first_state['orderPath'] + '/shipments', individual_outbound, blocked={409})
    second_state = complete_child(child_rows[1], str(cert_seed + 1), False)
    outbound_payload = {
        'carrierName': 'QA Master Return',
        'trackingNumber': 'QA-MASTER-OUT-' + suffix,
        'note': 'Disposable isolated master return parcel',
    }
    admin(f'/api/admin/merchant-batches/{batch_id}/outbound-shipment', outbound_payload, blocked={409})
    check(
        'one unpacked child blocks the master return without creating a shipment',
        scalar(f"SELECT COUNT(*) FROM merchant_batch_shipment WHERE batch_id={batch_id} AND direction_code='outbound'") == 0,
    )
    pack_child(second_state)
    scoped(second_state['orderPath'] + '/shipments', individual_outbound, blocked={409})

    unrelated_status = sql(f'SELECT status_code FROM grading_order WHERE id={int(unrelated_order_id)}')
    unrelated_events = scalar(
        'SELECT COUNT(*) FROM order_timeline_event '
        f"WHERE order_id={int(unrelated_order_id)} AND event_code IN ('batch_return_shipped','batch_delivered')"
    )
    outbound = admin(f'/api/admin/merchant-batches/{batch_id}/outbound-shipment', outbound_payload)
    check(
        'one master return shipment advances every packed child together',
        outbound['statusCode'] == 'return_shipped'
        and all(row['statusCode'] == 'return_shipped' for row in outbound['orders'])
        and len([row for row in outbound['shipments'] if row['directionCode'] == 'outbound']) == 1,
    )
    check(
        'master return does not create duplicate individual outbound shipments',
        scalar(
            'SELECT COUNT(*) FROM order_shipment '
            f"WHERE order_id IN ({','.join(str(value) for value in child_ids)}) AND direction_code='outbound'"
        ) == 0,
    )
    outbound_shipment = next(row for row in outbound['shipments'] if row['directionCode'] == 'outbound')
    delivered = admin(
        f'/api/admin/merchant-batches/{batch_id}/shipments/{int(outbound_shipment["id"])}/delivered', {}
    )
    check(
        'master return delivery synchronizes batch children and their physical items',
        delivered['statusCode'] == 'delivered'
        and all(row['statusCode'] == 'delivered' for row in delivered['orders'])
        and scalar(
            'SELECT COUNT(*) FROM grading_order_item '
            f"WHERE order_id IN ({','.join(str(value) for value in child_ids)}) AND status_code='delivered'"
        ) == len(child_ids),
    )
    child_events = scalar(
        'SELECT COUNT(*) FROM order_timeline_event '
        f"WHERE order_id IN ({','.join(str(value) for value in child_ids)}) "
        "AND event_code IN ('batch_return_shipped','batch_delivered')"
    )
    check('each child receives both master return and delivery timeline milestones', child_events == len(child_ids) * 2)
    check(
        'master-parcel updates do not mix in an unrelated order',
        sql(f'SELECT status_code FROM grading_order WHERE id={int(unrelated_order_id)}') == unrelated_status
        and scalar(
            'SELECT COUNT(*) FROM order_timeline_event '
            f"WHERE order_id={int(unrelated_order_id)} AND event_code IN ('batch_return_shipped','batch_delivered')"
        ) == unrelated_events,
    )
    return {'batchId': batch_id, 'childOrderIds': child_ids}


def create_scoped_staff(session, suffix, admin):
    """Create a non-admin employee with an ephemeral QA-only BCrypt password."""
    user_name = 'qscope_' + suffix
    role_key = 'qa_scope_' + suffix
    staff_password = secrets.token_urlsafe(12)[:18]
    staff_password_hash = bcrypt.hashpw(staff_password.encode(), bcrypt.gensalt()).decode()
    sql(
        "INSERT INTO sys_role "
        "(role_name,role_key,role_sort,data_scope,menu_check_strictly,dept_check_strictly,status,del_flag,create_by,create_time,remark) "
        f"VALUES ('QA scoped {suffix}','{role_key}',90,'1',1,1,'0','0','qa',NOW(),'Disposable isolated acceptance role')"
    )
    role_id = scalar(f"SELECT role_id FROM sys_role WHERE role_key='{role_key}'")
    sql(
        "INSERT INTO sys_user "
        "(dept_id,user_name,nick_name,user_type,email,phonenumber,sex,avatar,password,status,del_flag,create_by,create_time,remark) "
        f"VALUES (NULL,'{user_name}','QA Scoped Staff','00','','','2','','{staff_password_hash}',"
        "'0','0','qa',NOW(),'Disposable isolated acceptance user')"
    )
    user_id = scalar(f"SELECT user_id FROM sys_user WHERE user_name='{user_name}' AND del_flag='0'")
    check('restricted QA staff fixture has distinct non-admin user and role ids', user_id > 1 and role_id > 0)
    sql(f'INSERT INTO sys_user_role (user_id,role_id) VALUES ({user_id},{role_id})')
    permissions = (
        'nxr:order:list', 'nxr:order:manage', 'nxr:order:warehouse', 'nxr:order:grading',
        'nxr:order:shipping', 'nxr:order:batch',
        'nxr:entry:list', 'nxr:entry:add', 'nxr:entry:edit', 'nxr:entry:approve',
        'nxr:media:list', 'nxr:media:import', 'nxr:media:publish',
        'nxr:customer:list', 'nxr:export:list', 'nxr:export:generate', 'nxr:dashboard:view',
    )
    permission_sql = ','.join("'" + value + "'" for value in permissions)
    found = scalar(f'SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE perms IN ({permission_sql})')
    check('restricted QA role permissions exist in the migrated menu catalog', found == len(permissions))
    sql(
        'INSERT INTO sys_role_menu (role_id,menu_id) '
        f'SELECT {role_id},menu_id FROM sys_menu WHERE perms IN ({permission_sql})'
    )
    login = api('/login', {'username': user_name, 'password': staff_password})
    token = login['token']
    admin('/api/admin/commerce-policy/staff-scope', {
        'userId': user_id,
        'businessLineIds': [int(session['businessLine']['id'])],
        'workCenterIds': [int(session['workCenter']['id'])],
    }, method='PUT')
    return user_id, token


def main():
    # These guards precede login, SQL fixture creation, and every other mutation.
    assert iteration.database == EXPECTED_DATABASE, 'This script is bound to the named disposable QA database only'
    assert os.environ.get('NXR_QA_DATABASE') == EXPECTED_DATABASE, 'Exact disposable database environment marker required'
    assert iteration.context.get('database') == EXPECTED_DATABASE, 'QA context database mismatch'
    marker = iteration.context.get('priceMarker')
    assert isinstance(marker, str) and marker.startswith('QA acceptance '), 'Unique price marker missing'
    check(
        'runtime matches the exact isolated QA database marker before any fixture write',
        any(row['displayName'] == marker for row in api('/api/customer/service-prices')),
    )

    assert SESSION_PATH.exists(), 'Run verify-order-iteration.py first'
    assert stat.S_IMODE(SESSION_PATH.stat().st_mode) == 0o600, 'QA session file must remain owner-only'
    session = json.loads(SESSION_PATH.read_text())
    required = {
        'company', 'other', 'adminToken', 'password', 'paidOrder', 'baseOrder',
        'businessLine', 'workCenter', 'otherCenter', 'suffix',
    }
    assert required.issubset(session), 'First-stage QA session is incomplete'
    assert re.fullmatch(r'[0-9a-f]{8}', session['suffix']), 'Unexpected first-stage fixture suffix'

    suffix = session['suffix'] + secrets.token_hex(2)
    admin_token = session['adminToken']
    # Browser acceptance may have logged out a saved customer session. Refresh
    # both customer tokens from the owner-only fixture without exposing them.
    company = api('/api/customer/auth/login', {
        'email': session['company']['customer']['email'], 'password': session['password'],
    })
    other = api('/api/customer/auth/login', {
        'email': session['other']['customer']['email'], 'password': session['password'],
    })
    merchant_id = int(company['customer']['id'])
    admin = lambda path, payload=None, **kw: api(path, payload, admin_token, True, **kw)
    merchant = lambda path, payload=None, **kw: api(path, payload, company['token'], **kw)

    if '--batch-logistics-only' in sys.argv[1:]:
        shipping = create_qa_shipping_policy(admin, suffix)
        scoped_user_id, scoped_token = create_scoped_staff(session, suffix, admin)
        scoped = lambda path, payload=None, **kw: api(path, payload, scoped_token, True, **kw)
        cert_seed = 8900000000 + (int(secrets.token_hex(4), 16) % 90000000)
        logistics = verify_batch_logistics(
            session, suffix, admin, merchant, scoped, scoped_token,
            'weight_' + str(shipping['id']), cert_seed, int(session['paidOrder']['id']),
        )
        previous = json.loads(RESULT_PATH.read_text()) if RESULT_PATH.exists() else {'checks': []}
        assert previous.get('database', EXPECTED_DATABASE) == EXPECTED_DATABASE, 'Previous QA result database mismatch'
        combined_checks = list(previous.get('checks', [])) + qa.checks
        result = {
            **{key: value for key, value in previous.items() if key not in {'passed', 'checks'}},
            'database': EXPECTED_DATABASE,
            'passed': len(combined_checks),
            'checks': combined_checks,
            'logisticsBatchId': logistics['batchId'],
            'scopedLogisticsUserId': scoped_user_id,
            'realPayments': 0,
            'emailsSent': 0,
        }
        RESULT_PATH.write_text(json.dumps(result, ensure_ascii=False, indent=2))
        RESULT_PATH.chmod(0o600)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    # Aggregate policy fixtures use arbitrary QA-only values; they are not product defaults.
    price = admin('/api/admin/commerce-policy/price-policy', {
        'policyCode': 'qa_batch_tier_' + suffix,
        'displayName': 'QA aggregate batch tier ' + suffix,
        'customerSegmentCode': 'business',
        'customerId': merchant_id,
        'currencyCode': 'USD',
        'minimumQuantity': 3,
        'maximumQuantity': 3,
        'unitPrice': '7.25',
        'priorityNo': 999,
        'active': True,
    }, method='PUT')
    shipping = create_qa_shipping_policy(admin, suffix)
    shipping_code = 'weight_' + str(shipping['id'])
    photo = iteration.upload_photo(company['token'])
    first_order = order_payload(session['baseOrder'], ['Aggregate One'], shipping_code)
    second_order = order_payload(
        session['baseOrder'], ['Aggregate Two A', 'Aggregate Two B'], shipping_code, photo_id=photo['id']
    )
    parts = [{'reference': 'ROW-A', 'cardCount': 1}, {'reference': 'ROW-B', 'cardCount': 2}]
    quote = merchant('/api/customer/commerce/batch-quote-preview', {
        'customerId': merchant_id,
        'country': 'US',
        'currency': 'USD',
        'shippingOptionCode': shipping_code,
        'parts': parts,
    })
    standalone = []
    for count in (1, 2):
        query = (
            f'?customerId={merchant_id}&country=US&currency=USD&count={count}'
            f'&shippingOptionCode={shipping_code}'
        )
        standalone.append(merchant('/api/customer/commerce/quote-preview' + query))
    check(
        'aggregate quote selects the whole-batch quantity tier and one parcel policy',
        int(quote['aggregate']['pricePolicyId']) == int(price['id'])
        and quote['aggregate']['shippingOptionCode'] == shipping_code
        and decimal(quote['aggregate']['unitPrice']) == Decimal('7.25')
        and decimal(quote['aggregate']['returnShippingFee']) == Decimal('4.00'),
    )
    check(
        'aggregate return shipping is lower than charging each child parcel independently',
        decimal(quote['aggregate']['returnShippingFee'])
        < sum((decimal(row['returnShippingFee']) for row in standalone), Decimal('0')),
    )
    allocated = quote['allocations']
    check(
        'batch allocation preserves service shipping and total amounts to the cent',
        sum((decimal(row['serviceFee']) for row in allocated), Decimal('0')) == decimal(quote['aggregate']['serviceFee'])
        and sum((decimal(row['returnShippingFee']) for row in allocated), Decimal('0')) == decimal(quote['aggregate']['returnShippingFee'])
        and sum((decimal(row['totalAmount']) for row in allocated), Decimal('0')) == decimal(quote['aggregate']['totalAmount']),
    )

    batch = merchant('/api/customer/merchant/batches', {
        'sourceName': 'qa-order-operations-' + suffix,
        'batchName': 'QA aggregate batch ' + suffix,
        'orders': [
            batch_row('ROW-A', first_order, 'Private A'),
            batch_row('ROW-B', second_order, 'Private B'),
        ],
    })
    check('aggregate merchant batch creates both child orders atomically', batch['acceptedRows'] == 2 and batch['rejectedRows'] == 0)
    batch_rows = batch['rows']
    child_ids = [int(row['orderId']) for row in batch_rows]
    stored = sql(
        'SELECT order_no,total_card_count,service_fee,return_shipping_fee,total_amount,quoted_unit_price,'
        'commerce_price_policy_id,commerce_shipping_policy_id,shipping_source_code '
        f"FROM grading_order WHERE id IN ({','.join(str(value) for value in child_ids)}) ORDER BY order_no"
    ).splitlines()
    stored_rows = [row.split('\t') for row in stored]
    check(
        'frozen child orders retain aggregate policy ids and unit price',
        len(stored_rows) == 2
        and all(decimal(row[5]) == Decimal('7.25') for row in stored_rows)
        and all(int(row[6]) == int(price['id']) and int(row[7]) == int(shipping['id']) for row in stored_rows)
        and all(row[8] == 'weight_policy' for row in stored_rows),
    )
    check(
        'frozen child fees sum to the one aggregate quote',
        sum((decimal(row[2]) for row in stored_rows), Decimal('0')) == decimal(quote['aggregate']['serviceFee'])
        and sum((decimal(row[3]) for row in stored_rows), Decimal('0')) == decimal(quote['aggregate']['returnShippingFee'])
        and sum((decimal(row[4]) for row in stored_rows), Decimal('0')) == decimal(quote['aggregate']['totalAmount']),
    )

    counts = fixture_counts()
    invalid_order = order_payload(session['baseOrder'], [''], shipping_code)
    merchant('/api/customer/merchant/batches', {
        'sourceName': 'qa-atomic-failure-' + suffix,
        'orders': [batch_row('ATOMIC-A', first_order, 'Valid first row'), batch_row('ATOMIC-B', invalid_order, 'Invalid second row')],
    }, blocked={400, 409})
    check('a late invalid batch row rolls back its earlier child and every batch record', fixture_counts() == counts)

    different_address = order_payload(
        session['baseOrder'], ['Different Address'], shipping_code, address_line='2 Deliberately Different QA Street'
    )
    merchant('/api/customer/merchant/batches', {
        'sourceName': 'qa-address-failure-' + suffix,
        'orders': [batch_row('ADDRESS-A', first_order, 'Address A'), batch_row('ADDRESS-B', different_address, 'Address B')],
    }, blocked={400})
    check('a master parcel rejects mixed return addresses without partial writes', fixture_counts() == counts)

    private_row = batch_rows[0]
    private_token = private_row['trackingToken']
    public = secret_api(
        '/api/public/merchant-order-tracking/' + private_token,
        'new private tracking link resolves without exposing its token in QA output',
    )
    check(
        'private tracking response exposes only the selected customer order status surface',
        public['orderNo'] == private_row['orderNo']
        and not {'email', 'customer', 'customerId', 'clientContactHint'}.intersection(json_keys(public)),
    )
    token_hash = sql(
        'SELECT token_hash FROM merchant_batch_tracking_token t '
        'JOIN merchant_order_batch_item i ON i.id=t.batch_item_id '
        f"WHERE i.order_id={int(private_row['orderId'])} AND t.status_code='active' ORDER BY t.id DESC LIMIT 1"
    )
    check('private tracking token is persisted only as a one-way hash', token_hash and token_hash != private_token)
    api(f'/api/customer/merchant/batches/{batch["batchNo"]}', token=other['token'], blocked={403, 404})
    api(
        f'/api/customer/merchant/batches/{batch["batchNo"]}/orders/{private_row["orderNo"]}/tracking-token/rotate',
        token=other['token'], blocked={403, 404}, method='POST',
    )
    rotated = merchant(
        f'/api/customer/merchant/batches/{batch["batchNo"]}/orders/{private_row["orderNo"]}/tracking-token/rotate',
        {},
    )
    new_private_token = rotated['trackingToken']
    check('rotating a private tracking link issues a distinct value', new_private_token != private_token)
    secret_api(
        '/api/public/merchant-order-tracking/' + private_token,
        'rotation immediately revokes the previous private link', expected_status=404,
    )
    secret_api(
        '/api/public/merchant-order-tracking/' + new_private_token,
        'rotated private tracking link resolves for the same child order',
    )
    merchant(
        f'/api/customer/merchant/batches/{batch["batchNo"]}/orders/{private_row["orderNo"]}/tracking-token',
        method='DELETE',
    )
    secret_api(
        '/api/public/merchant-order-tracking/' + new_private_token,
        'explicit revocation invalidates the rotated private link', expected_status=404,
    )
    private_token = new_private_token = None

    # Move one fixture order to the other centre only after validating its frozen quote.
    inaccessible_order_id = child_ids[1]
    other_center_id = int(session['otherCenter']['id'])
    sql(f'UPDATE grading_order SET work_center_id={other_center_id} WHERE id={inaccessible_order_id}')
    admin_photo, admin_photo_headers = raw(f'/api/admin/order-photos/{photo["id"]}', admin_token, admin=True)
    check(
        'unrestricted administrator can read the attached private photo without browser caching',
        admin_photo[:2] == b'\xff\xd8' and 'no-store' in admin_photo_headers.get('Cache-Control', ''),
    )

    owned_line = admin('/api/admin/commerce-policy/business-line', {
        'lineCode': 'qa_owned_' + suffix,
        'displayName': 'QA owned inventory ' + suffix,
        'orderOriginCode': 'owned_inventory',
        'defaultLine': False,
        'active': True,
    }, method='PUT')
    cert_seed = 8000000000 + (int(secrets.token_hex(4), 16) % 900000000)
    inaccessible_cert = str(cert_seed)
    accessible_cert = str(cert_seed + 1)
    other_submission = admin('/api/admin/submissions', submission_payload(inaccessible_cert, 'Other Centre'))
    admin('/api/admin/commerce-policy/submission-routing', {
        'submissionId': int(other_submission['id']),
        'businessLineId': int(owned_line['id']),
        'workCenterId': other_center_id,
    }, method='PUT')
    other_submission = admin(f'/api/admin/submissions/{int(other_submission["id"])}/approve', {})

    scoped_user_id, scoped_token = create_scoped_staff(session, suffix, admin)
    scoped = lambda path, payload=None, **kw: api(path, payload, scoped_token, True, **kw)
    scoped_orders = scoped('/api/admin/orders?page=1&pageSize=100')
    visible_order_ids = {int(row['id']) for row in scoped_orders['items']}
    paid_order_id = int(session['paidOrder']['id'])
    check(
        'restricted order list includes its mapped order and excludes another work centre',
        paid_order_id in visible_order_ids and inaccessible_order_id not in visible_order_ids,
    )
    scoped(f'/api/admin/orders/{inaccessible_order_id}', blocked={404})
    scoped(f'/api/admin/order-photos/{photo["id"]}', blocked={404})
    scoped(f'/api/admin/submissions/{int(other_submission["id"])}', blocked={404})
    scoped(f'/api/admin/media/submissions/{int(other_submission["id"])}/publish', {}, blocked={404})
    hidden_media = scoped('/api/admin/media/queue?certId=' + inaccessible_cert + '&page=1&pageSize=20')
    check('restricted media queue excludes an inaccessible routed submission', hidden_media['total'] == 0)
    scoped('/api/admin/customers?page=1&pageSize=10', blocked={403})
    scoped('/api/admin/exports?page=1&pageSize=10', blocked={403})
    scoped('/api/admin/dashboard', blocked={403})
    scoped('/api/admin/submissions', submission_payload(str(cert_seed + 2), 'Unassigned'), blocked={403})
    scoped(f'/api/admin/merchant-batches/{int(batch["batchId"])}', blocked={404})

    # Prepare a real foreign-order barcode with the unrestricted administrator.
    foreign_workbench = admin(f'/api/admin/orders/{inaccessible_order_id}/workbench/start', {})
    foreign_barcode = foreign_workbench['items'][0]['barcode']

    paid_order_no = session['paidOrder']['orderNo']
    paid_path = f'/api/admin/orders/{paid_order_id}'
    paid_detail = scoped(paid_path)
    merchant(f'/api/customer/orders/{paid_order_no}/inbound-shipment', {
        'direction': 'inbound',
        'carrierName': 'QA Inbound Only',
        'trackingNumber': 'QA-IN-' + suffix,
        'note': 'Disposable isolated inbound parcel',
    })
    workbench = scoped(paid_path + '/workbench/start', {})
    session_id = int(workbench['activeSession']['id'])
    item = workbench['items'][0]
    item_id = int(item['orderItemId'])
    barcode = item['barcode']
    create_path = paid_path + f'/items/{item_id}/submission'
    scoped(create_path, submission_payload(accessible_cert, 'Scoped Before Scan'), blocked={409})
    scoped(paid_path + '/workbench/scan', {
        'sessionId': session_id, 'stage': 'intake', 'barcode': foreign_barcode,
    }, blocked={409})
    scanned = scoped(paid_path + '/workbench/scan', {
        'sessionId': session_id, 'stage': 'intake', 'barcode': barcode,
    })
    check('mapped workbench employee scans the expected physical card into intake', scanned['items'][0]['intakeScannedAt'] is not None)
    scoped(paid_path + '/workbench/scan', {
        'sessionId': session_id, 'stage': 'intake', 'barcode': barcode,
    }, blocked={409})
    operations = scoped(paid_path + '/intake/receive', {
        'intakeCode': paid_detail['intakeCode'],
        'packageNo': 'QA-PACK-' + suffix,
        'receivedCount': 1,
        'conditionNote': 'Count and condition match',
        'exceptionTypes': [],
    })
    check('scanned warehouse intake advances the order and creates initial tasks', len(operations['receipts']) == 1 and len(operations['workTasks']) >= 2)

    created = scoped(create_path, submission_payload(accessible_cert, 'Scoped Workbench'))
    submission_id = int(created['submission']['id'])
    route = sql(
        'SELECT order_origin_code,business_line_id,work_center_id FROM grading_submission '
        f'WHERE id={submission_id}'
    ).split('\t')
    check(
        'scoped grading record inherits the locked order origin line and centre',
        route == [
            'customer_submission', str(int(session['businessLine']['id'])), str(int(session['workCenter']['id']))
        ],
    )
    scoped(create_path, submission_payload(str(cert_seed + 3), 'Duplicate Item'), blocked={409})
    approved = scoped(f'/api/admin/submissions/{submission_id}/approve', {})
    check('restricted reviewer can approve only the newly scoped order grading record', approved['statusCode'] == 'approved')

    operations = scoped(paid_path + '/operations')
    required_tasks = {'preprocess', 'vision', 'manual_review', 'encapsulation'}
    task_types = {row['taskTypeCode'] for row in operations['workTasks']}
    check('linking scoped grading work creates the full order task set', required_tasks.issubset(task_types))
    for task in operations['workTasks']:
        if task['taskTypeCode'] in required_tasks and task['statusCode'] != 'completed':
            operations = scoped(paid_path + f'/tasks/{int(task["id"])}', {
                'statusCode': 'completed',
                'resultSummary': 'Completed in isolated QA workbench',
                'failureReason': None,
            })
    scoped(paid_path + '/status', {'statusCode': 'review', 'detail': 'QA grading review complete'})
    scoped(paid_path + '/quality-check', {'passed': True, 'note': 'QA final check passed'})
    check('task completion and final review advance the order to completed', scoped(paid_path)['statusCode'] == 'completed')

    label_bytes, label_headers = binary_post(paid_path + '/workbench/label-export', scoped_token, {})
    check(
        'order-scoped label export contains only the approved card and disables caching',
        accessible_cert.encode() in label_bytes and inaccessible_cert.encode() not in label_bytes
        and 'no-store' in label_headers.get('Cache-Control', ''),
    )
    labeled_snapshot = scoped(paid_path + '/workbench')
    label_barcode = labeled_snapshot['items'][0]['labelBarcode']
    check('first label export creates the versioned label barcode required by later scans', bool(label_barcode))
    scoped(paid_path + '/workbench/scan', {
        'sessionId': session_id, 'stage': 'label', 'barcode': label_barcode,
    })
    scoped(paid_path + '/workbench/scan', {
        'sessionId': session_id, 'stage': 'packing', 'barcode': label_barcode,
    })
    manifest_bytes, manifest_headers = binary_post(paid_path + '/workbench/manifest-export', scoped_token, {})
    check(
        'order-scoped packing manifest records all three physical scan stages',
        barcode.encode() in manifest_bytes and b'true,true,true' in manifest_bytes
        and 'no-store' in manifest_headers.get('Cache-Control', ''),
    )
    packed = scoped(paid_path + '/workbench/packing-check', {'sessionId': session_id})
    check('packing verification passes once and closes the employee workbench session', packed['packingCheck']['statusCode'] == 'passed' and packed['activeSession'] is None)
    shipped = scoped(paid_path + '/shipments', {
        'direction': 'outbound',
        'carrierName': 'QA Return Only',
        'trackingNumber': 'QA-OUT-' + suffix,
        'note': 'Disposable isolated return shipment',
    })
    check(
        'passed scoped packing permits one outbound shipment and advances return shipping',
        shipped['statusCode'] == 'return_shipped'
        and any(row['directionCode'] == 'outbound' for row in shipped['shipments']),
    )

    visible_submission = scoped('/api/admin/submissions?page=1&pageSize=100&query=' + accessible_cert)
    check(
        'restricted submission list includes its linked record and excludes the other business line',
        any(int(row['id']) == submission_id for row in visible_submission['items'])
        and all(int(row['id']) != int(other_submission['id']) for row in visible_submission['items']),
    )
    visible_media = scoped('/api/admin/media/queue?certId=' + accessible_cert + '&page=1&pageSize=20')
    check('restricted media queue includes its approved linked grading record', any(int(row['submissionId']) == submission_id for row in visible_media['items']))
    logistics = verify_batch_logistics(
        session, suffix + 'l', admin, merchant, scoped, scoped_token, shipping_code,
        cert_seed + 10, child_ids[0],
    )

    # Result deliberately excludes all passwords, bearer/customer tokens, tracking tokens, and barcodes.
    result = {
        'database': EXPECTED_DATABASE,
        'passed': len(qa.checks),
        'checks': qa.checks,
        'aggregateBatchId': int(batch['batchId']),
        'scopedUserId': scoped_user_id,
        'scopedSubmissionId': submission_id,
        'logisticsBatchId': logistics['batchId'],
        'workflowOrderNo': paid_order_no,
        'realPayments': 0,
        'emailsSent': 0,
    }
    RESULT_PATH.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    RESULT_PATH.chmod(0o600)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
