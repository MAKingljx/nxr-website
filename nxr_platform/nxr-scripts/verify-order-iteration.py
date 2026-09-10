#!/usr/bin/env python3
"""Acceptance for the isolated Java application/review/batch/workbench runtime."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import secrets
import struct
import subprocess
import urllib.parse
import urllib.request
import urllib.error
import zlib
from decimal import Decimal

spec = importlib.util.spec_from_file_location('commerce_qa', Path(__file__).with_name('verify-customer-commerce.py'))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
api, check = qa.api, qa.check
context_path = Path('/tmp/nxr-iteration-qa-context.json')
context = json.loads(context_path.read_text())
database = context['database']
assert database.startswith('nxr_acceptance_iteration_'), 'Disposable QA database only'
assert os.environ.get('NXR_QA_DATABASE') == database, 'Explicit database marker required'


def sql(statement):
    return subprocess.check_output(['mysql', '-uroot', '-N', database, '-e', statement], text=True).strip()


def png():
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 32, 48, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\x30\x70\xc0' * 32) * 48)) + chunk(b'IEND', b'')


def upload_photo(token):
    boundary = 'NxrQaBoundary' + secrets.token_hex(8)
    payload = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="qa-card.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png() + ('\r\n--' + boundary + '--\r\n').encode()
    req = urllib.request.Request(qa.BASE + '/api/customer/order-photos', data=payload, headers={'Content-Type': 'multipart/form-data; boundary=' + boundary, 'X-NXR-Customer-Token': token})
    with qa.http.open(req, timeout=30) as response:
        data = json.load(response)
    assert 'id' in data, str(data)
    return data


def raw(path, token, admin=False, payload=None):
    headers = {'Authorization' if admin else 'X-NXR-Customer-Token': ('Bearer ' if admin else '') + token}
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(qa.BASE + path, data=json.dumps(payload).encode() if payload is not None else None, headers=headers)
    with qa.http.open(request, timeout=30) as response:
        return response.read(), response.headers


def main():
    check('runtime matches isolated QA marker before any fixture write', any(p['displayName'] == context['priceMarker'] for p in api('/api/customer/service-prices')))
    suffix = secrets.token_hex(4)
    password = 'Nxr-Isolated-Iteration-2026!'
    admin_token = api('/login', {'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')})['token']
    staff = lambda path, payload=None, **kw: api(path, payload, admin_token, True, **kw)
    line = staff('/api/admin/commerce-policy/business-line', {'lineCode': 'qa_customer_' + suffix, 'displayName': 'QA customer submissions', 'orderOriginCode': 'customer_submission', 'defaultLine': True, 'active': True}, method='PUT')
    center = staff('/api/admin/commerce-policy/work-center', {'centerCode': 'qa_center_a_' + suffix, 'displayName': 'QA processing center A', 'defaultCenter': True, 'active': True}, method='PUT')
    other_center = staff('/api/admin/commerce-policy/work-center', {'centerCode': 'qa_center_b_' + suffix, 'displayName': 'QA processing center B', 'defaultCenter': False, 'active': True}, method='PUT')
    collector = api('/api/customer/auth/register', {'email': f'iteration-collector-{suffix}@example.invalid', 'password': password, 'displayName': 'QA Collector'})
    company = api('/api/customer/auth/register', {'email': f'iteration-company-{suffix}@example.invalid', 'password': password, 'displayName': 'QA Agent'})
    other = api('/api/customer/auth/register', {'email': f'iteration-other-{suffix}@example.invalid', 'password': password, 'displayName': 'QA Other'})
    staff(f'/api/admin/customers/{company["customer"]["id"]}/type', {'accountTypeCode': 'merchant'}, method='PUT')
    customer = lambda path, payload=None, **kw: api(path, payload, company['token'], **kw)
    public_customer = lambda path, payload=None, **kw: api(path, payload, collector['token'], **kw)
    photo = upload_photo(collector['token'])
    preview, headers = raw(f'/api/customer/order-photos/{photo["id"]}', collector['token'])
    check('private photo is a compressed image served without browser caching', preview[:2] == b'\xff\xd8' and 'no-store' in headers.get('Cache-Control', ''))
    api(f'/api/customer/order-photos/{photo["id"]}', token=other['token'], blocked={404})
    api(f'/api/customer/order-photos/{photo["id"]}', token=other['token'], method='DELETE', blocked={404})
    capacity = staff('/api/admin/order-photos/capacity')
    check('capacity headroom is visible to authorized staff', capacity['minimumFreeBytes'] > 0 and 'checkedAt' in capacity)
    config = customer('/api/customer/order-admission/config')
    check('application limit is configurable beyond the former 30 cards', config['maxCardsPerOrder'] >= 200)
    option = api('/api/customer/shipping-options?country=US&currencyCode=USD')[0]['optionCode']
    base = {'serviceLevel': 'basic_grading', 'currencyCode': 'USD', 'returnShippingOptionCode': option,
            'contactName': 'QA Agent', 'contactPhone': '+1 555 0100', 'returnAddressLine1': '1 QA Street',
            'returnCity': 'Los Angeles', 'returnRegion': 'CA', 'returnPostalCode': '90001', 'returnCountry': 'US',
            'saveReturnAddress': False, 'languageGroups': [], 'customerNote': 'Isolated acceptance fixture',
            'items': [{'cardName': '自由卡名 Prototype', 'year': '2026', 'rarity': 'Unlisted rarity', 'productType': 'graded_card', 'category': 'trading_card', 'languageCode': 'EN'}]}

    def quote(account, count=1, shipping=option):
        query = urllib.parse.urlencode({'customerId': account['customer']['id'], 'country': 'US', 'currency': 'USD', 'count': count, 'shippingOptionCode': shipping})
        return api('/api/customer/commerce/quote-preview?' + query, token=account['token'])

    quoted = quote(collector)
    order = public_customer('/api/customer/orders', {**base, 'quotedTotalAmount': quoted['totalAmount'], 'quotedCurrencyCode': 'USD', 'items': [{**base['items'][0], 'frontPhotoId': photo['id']}]})
    path = '/api/customer/orders/' + order['orderNo']
    check('free card metadata and private photo survive application creation', order['items'][0]['year'] == '2026' and order['items'][0]['frontPhotoId'] == photo['id'])
    check('new orders enter admission instead of immediate payment', order['statusCode'] == 'admission_review' and order['admissionStatus'] == 'pending_review')
    public_customer(path + '/checkout', {'provider': 'paypal', 'idempotencyKey': 'unreviewed'}, blocked={409})
    public_customer(path + '/payment-proof', {'provider': 'bank_transfer', 'payerReference': 'not-accepted'}, blocked={409})
    public_customer(f'/api/customer/order-photos/{photo["id"]}', method='DELETE', blocked={409})
    public_customer('/api/customer/orders', {**base, 'items': [{**base['items'][0], 'frontPhotoId': photo['id']}]}, blocked={400, 409})
    public_customer('/api/customer/orders', {**base, 'quotedTotalAmount': Decimal(quoted['totalAmount']).__str__() + '1', 'quotedCurrencyCode': 'USD'}, blocked={400, 409})
    api(path + '/admission', token=other['token'], blocked={404})

    def decision(target, code='approve', note='QA reviewed eligibility'):
        admission = staff(f'/api/admin/order-admissions/{target["id"]}')
        return staff(f'/api/admin/order-admissions/{target["id"]}/decision', {'decision': code, 'note': note, 'expectedRevision': admission['admissionRevision']})

    requested = decision(order, 'request_information', 'Please provide the card back and edition.')
    extra = upload_photo(collector['token'])
    resubmitted = public_customer(path + '/admission/resubmit', {'note': 'Edition details supplied', 'supplementalPhotoIds': [extra['id']]})
    check('supplemental information and images return to review', resubmitted['admissionStatus'] == 'pending_review' and extra['id'] in resubmitted['supplementalPhotoIds'])
    approved = decision(order)
    check('approval opens confirmation and includes an unambiguous payment deadline', approved['canAcceptTerms'] and not approved['canPay'] and ('+' in approved['paymentDueAtIso'] or approved['paymentDueAtIso'].endswith('Z')))
    public_customer(path + '/checkout', {'provider': 'paypal', 'idempotencyKey': 'no-terms'}, blocked={409})
    public_customer(path + '/admission/accept-terms', {'termsVersion': 'wrong', 'acceptedQuotedAmount': order['totalAmount'], 'acceptedCurrency': 'USD'}, blocked={409})
    accepted = public_customer(path + '/admission/accept-terms', {'termsVersion': approved['termsVersion'], 'acceptedQuotedAmount': order['totalAmount'], 'acceptedCurrency': 'USD'})
    check('confirming the exact quote and terms enables payment', accepted['canPay'])
    sql(f"UPDATE grading_order SET payment_due_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id={int(order['id'])}")
    expired = public_customer(path + '/admission')
    check('expired applications cannot start payment', expired['paymentExpired'] and not expired['canPay'])
    public_customer(path + '/payment-proof', {'provider': 'bank_transfer', 'payerReference': 'expired'}, blocked={409})
    old_revision = expired['admissionRevision']
    renewed = decision(order)
    staff(f'/api/admin/order-admissions/{order["id"]}/decision', {'decision': 'reject', 'note': 'stale page', 'expectedRevision': old_revision}, blocked={409})
    check('renewal requires fresh terms confirmation', renewed['canAcceptTerms'] and not renewed['canPay'])

    # Company funds are simulated receipts only inside the disposable database.
    recharge = customer('/api/customer/merchant/wallet-recharges', {'currencyCode': 'USD', 'amount': '3000.00', 'providerCode': 'bank_transfer', 'payerReference': 'QA-' + suffix})
    staff(f'/api/admin/customers/{company["customer"]["id"]}/wallet-recharges/{recharge["id"]}/confirm', {'providerTransactionId': 'QA-' + suffix, 'note': 'Simulated isolated receipt'})
    merchant_order = customer('/api/customer/orders', base)
    merchant_path = '/api/customer/orders/' + merchant_order['orderNo']
    customer(merchant_path + '/wallet-payment', {'idempotencyKey': 'pending-' + suffix}, blocked={409})
    merchant_approval = decision(merchant_order)
    customer(merchant_path + '/admission/accept-terms', {'termsVersion': merchant_approval['termsVersion'], 'acceptedQuotedAmount': merchant_order['totalAmount'], 'acceptedCurrency': 'USD'})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        paid = list(pool.map(lambda _: customer(merchant_path + '/wallet-payment', {'idempotencyKey': 'pay-' + suffix}), range(2)))
    check('concurrent confirmed company payments debit once', all(row['statusCode'] == 'awaiting_inbound' for row in paid) and Decimal(str(customer('/api/customer/merchant/wallets')[0]['balance'])) == Decimal('3000') - Decimal(str(merchant_order['totalAmount'])))
    paid_admission = staff(f'/api/admin/order-admissions/{merchant_order["id"]}')
    staff(f'/api/admin/order-admissions/{merchant_order["id"]}/decision', {'decision': 'approve', 'note': 'must not reopen paid order', 'expectedRevision': paid_admission['admissionRevision']}, blocked={409})
    customer(merchant_path + '/admission/accept-terms', {'termsVersion': merchant_approval['termsVersion'], 'acceptedQuotedAmount': merchant_order['totalAmount'], 'acceptedCurrency': 'USD'}, blocked={409})
    check('repeat admission actions cannot regress a paid order', customer(merchant_path)['statusCode'] == 'awaiting_inbound')

    fixtures = {'collector': collector, 'company': company, 'other': other, 'adminToken': admin_token, 'password': password,
                'reviewOrder': order, 'paidOrder': merchant_order, 'baseOrder': base, 'suffix': suffix,
                'businessLine': line, 'workCenter': center, 'otherCenter': other_center}
    session_path = Path('/tmp/nxr-iteration-qa-session.json')
    session_path.write_text(json.dumps(fixtures, ensure_ascii=False)); session_path.chmod(0o600)
    result = {'passed': len(qa.checks), 'checks': qa.checks, 'database': database, 'realPayments': 0, 'emailsSent': 0,
              'applicationOrderNo': order['orderNo'], 'paidOrderNo': merchant_order['orderNo']}
    result_path = Path('/tmp/nxr-iteration-api-results.json')
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2)); result_path.chmod(0o600)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
