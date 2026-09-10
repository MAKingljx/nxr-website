#!/usr/bin/env python3
"""Verify admission and payment financial boundaries on the isolated QA database.

This phase reuses the fixtures created by verify-order-iteration.py, but logs the
test customer in again so a browser logout cannot invalidate the run. All gateway
channels must remain disabled. Verified late funds are represented by a local
manual-proof finance confirmation; this script never contacts a payment provider.
"""
from copy import deepcopy
from decimal import Decimal
import importlib.util
import json
import os
from pathlib import Path
import secrets


SCRIPT_DIR = Path(__file__).resolve().parent
iteration_spec = importlib.util.spec_from_file_location(
    'nxr_order_iteration', SCRIPT_DIR / 'verify-order-iteration.py'
)
iteration = importlib.util.module_from_spec(iteration_spec)
iteration_spec.loader.exec_module(iteration)

api, check, sql = iteration.api, iteration.check, iteration.sql
qa = iteration.qa
context = iteration.context
session_path = Path('/tmp/nxr-iteration-qa-session.json')
result_path = Path('/tmp/nxr-order-payment-iteration-results.json')


def message(response):
    if not isinstance(response, dict):
        return ''
    return str(response.get('message') or response.get('msg') or '')


def main():
    database = context['database']
    marker = context['priceMarker']
    assert database.startswith('nxr_acceptance_iteration_'), 'Disposable iteration QA database only'
    assert os.environ.get('NXR_QA_DATABASE') == database, 'Exact disposable QA database marker required'
    assert os.environ.get('NXR_QA_PRICE_MARKER') == marker, 'Exact disposable QA price marker required'
    assert context.get('base') == qa.BASE == 'http://127.0.0.1:8090', 'Loopback QA service only'

    # This anonymous read proves the running process is attached to the exact
    # isolated database before login, SQL fixture changes, or audited writes.
    check(
        'running backend matches the exact isolated price marker',
        any(row['displayName'] == marker for row in api('/api/customer/service-prices'))
    )
    check('mysql client is pinned to the exact disposable database', sql('SELECT DATABASE()') == database)

    session = json.loads(session_path.read_text())
    password = session['password']
    company = api('/api/customer/auth/login', {
        'email': session['company']['customer']['email'], 'password': password
    })
    admin_token = api('/login', {
        'username': 'admin', 'password': os.environ.get('NXR_QA_ADMIN_PASSWORD', 'admin123')
    })['token']
    customer = lambda path, payload=None, **kw: api(path, payload, company['token'], **kw)
    staff = lambda path, payload=None, **kw: api(path, payload, admin_token, True, **kw)
    customer_id = int(company['customer']['id'])
    suffix = secrets.token_hex(5)
    base_order = deepcopy(session['baseOrder'])
    base_order.pop('quotedTotalAmount', None)
    base_order.pop('quotedCurrencyCode', None)

    check(
        'all provider adapters remain disabled for network-free financial QA',
        all(not row['enabled'] for row in staff('/api/admin/payment-settings'))
    )

    def create_order():
        return customer('/api/customer/orders', deepcopy(base_order))

    def admission(order):
        return staff(f'/api/admin/order-admissions/{int(order["id"])}')

    def approve(order, note):
        current = admission(order)
        return staff(
            f'/api/admin/order-admissions/{int(order["id"])}/decision',
            {'decision': 'approve', 'note': note, 'expectedRevision': current['admissionRevision']}
        )

    def accept(order, approved):
        return customer(
            f'/api/customer/orders/{order["orderNo"]}/admission/accept-terms',
            {
                'termsVersion': approved['termsVersion'],
                'acceptedQuotedAmount': order['totalAmount'],
                'acceptedCurrency': order['currencyCode']
            }
        )

    def ready_order(label):
        order = create_order()
        approved = approve(order, f'QA approved {label}')
        accept(order, approved)
        return order, approved

    # Generic order-status mutation must not substitute for admission decisions.
    bypass = create_order()
    bypass_path = f'/api/admin/orders/{int(bypass["id"])}/status'
    staff(bypass_path, {'statusCode': 'awaiting_payment', 'detail': 'unsafe bypass'}, blocked={409})
    staff(bypass_path, {'statusCode': 'awaiting_inbound', 'detail': 'unsafe bypass'}, blocked={409})
    bypass_after = customer(f'/api/customer/orders/{bypass["orderNo"]}')
    check(
        'generic admin status cannot bypass application review',
        bypass_after['statusCode'] == 'admission_review' and bypass_after['admissionStatus'] == 'pending_review'
    )

    # Existing rows created before admission metadata remain payable only while
    # their operational state is still a payment state.
    legacy = create_order()
    legacy_id = int(legacy['id'])
    sql(f"""
        UPDATE grading_order SET status_code='awaiting_payment', admission_status_code=NULL,
          admission_revision=0, admission_submitted_at=NULL, admission_decided_at=NULL,
          admission_decision_note=NULL, admission_reviewed_by_user_id=NULL,
          payment_due_at=NULL, payment_deadline_status_code=NULL,
          approved_terms_version=NULL, approved_terms_text=NULL, approved_turnaround_text=NULL,
          approved_quote_amount=NULL, approved_quote_currency=NULL,
          accepted_terms_version=NULL, terms_accepted_at=NULL
        WHERE id={legacy_id}
    """)
    legacy_path = f'/api/customer/orders/{legacy["orderNo"]}/admission'
    legacy_payable = customer(legacy_path)
    check('legacy NULL admission metadata remains payable in an old payment state',
          legacy_payable['legacyOrder'] and legacy_payable['canPay'])
    sql(f"UPDATE grading_order SET status_code='awaiting_inbound' WHERE id={legacy_id}")
    legacy_paid = customer(legacy_path)
    check('legacy NULL admission metadata never reopens payment after the payment state',
          legacy_paid['legacyOrder'] and not legacy_paid['canPay'])

    # Approval freezes both quote and terms. Later global config changes do not
    # rewrite the order snapshot, while a changed order amount invalidates it.
    frozen = create_order()
    frozen_approval = approve(frozen, 'QA freeze quote and terms')
    original_config = staff('/api/admin/order-admissions/config')
    changed_config = {
        'paymentDeadlineHours': original_config['paymentDeadlineHours'],
        'maxCardsPerOrder': original_config['maxCardsPerOrder'],
        'termsVersion': f'qa-terms-{suffix}',
        'termsText': 'Disposable QA terms revision; existing approvals must remain frozen.',
        'turnaroundText': original_config['turnaroundText']
    }
    try:
        staff('/api/admin/order-admissions/config', changed_config, method='PUT')
        frozen_after_config = customer(f'/api/customer/orders/{frozen["orderNo"]}/admission')
        check(
            'approved order retains its terms and quote after global config changes',
            frozen_after_config['termsVersion'] == frozen_approval['termsVersion']
            and frozen_after_config['termsText'] == frozen_approval['termsText']
            and Decimal(str(frozen_after_config['quoteAmount'])) == Decimal(str(frozen['totalAmount']))
        )
    finally:
        staff('/api/admin/order-admissions/config', {
            'paymentDeadlineHours': original_config['paymentDeadlineHours'],
            'maxCardsPerOrder': original_config['maxCardsPerOrder'],
            'termsVersion': original_config['termsVersion'],
            'termsText': original_config['termsText'],
            'turnaroundText': original_config['turnaroundText']
        }, method='PUT')
    sql(f"UPDATE grading_order SET total_amount=total_amount+1.00 WHERE id={int(frozen['id'])}")
    frozen_changed = customer(f'/api/customer/orders/{frozen["orderNo"]}/admission')
    customer(
        f'/api/customer/orders/{frozen["orderNo"]}/admission/accept-terms',
        {
            'termsVersion': frozen_approval['termsVersion'],
            'acceptedQuotedAmount': frozen['totalAmount'],
            'acceptedCurrency': frozen['currencyCode']
        },
        blocked={409}
    )
    check('a post-approval amount change closes terms acceptance and payment',
          not frozen_changed['canAcceptTerms'] and not frozen_changed['canPay'])

    # A submitted transfer proof owns the receivable until finance resolves it.
    proof, _ = ready_order('manual proof ownership')
    proof_path = f'/api/customer/orders/{proof["orderNo"]}'
    customer(proof_path + '/payment-proof', {
        'provider': 'bank_transfer', 'payerReference': f'QA-PROOF-{suffix}',
        'proofReference': 'Disposable local proof reference'
    })
    checkout_block = customer(
        proof_path + '/checkout',
        {'provider': 'paypal', 'idempotencyKey': f'qa-proof-checkout-{suffix}'},
        blocked={409}
    )
    customer(proof_path + '/wallet-payment', {'idempotencyKey': f'qa-proof-wallet-{suffix}'}, blocked={409})
    customer(proof_path + '/cancel', {'reason': 'must remain in finance review'}, blocked={409})
    proof_after = customer(proof_path)
    check(
        'manual proof blocks checkout, wallet debit and cancellation',
        'financial review' in message(checkout_block).lower()
        and proof_after['statusCode'] == 'payment_review'
        and proof_after['payments'][0]['statusCode'] == 'proof_submitted'
    )

    # Finance-confirmed funds after expiry are recorded but must not open intake.
    late, _ = ready_order('late verified funds')
    late_path = f'/api/customer/orders/{late["orderNo"]}'
    late_with_proof = customer(late_path + '/payment-proof', {
        'provider': 'bank_transfer', 'payerReference': f'QA-LATE-{suffix}',
        'proofReference': 'Disposable late payment proof'
    })
    late_payment_id = int(late_with_proof['payments'][0]['id'])
    late_id = int(late['id'])
    sql(f"""
        UPDATE grading_order SET payment_due_at=DATE_SUB(NOW(), INTERVAL 1 MINUTE),
          payment_deadline_status_code='expired', status_code='payment_expired'
        WHERE id={late_id}
    """)
    staff(
        f'/api/admin/orders/{late_id}/payments/{late_payment_id}/confirm',
        {'providerTransactionId': f'QA-LATE-TX-{suffix}', 'note': 'Verified after the deadline'}
    )
    late_after = customer(late_path)
    staff(
        f'/api/admin/orders/{late_id}/payments/{late_payment_id}/confirm',
        {'providerTransactionId': f'QA-LATE-TX-{suffix}', 'note': 'Duplicate confirmation'},
        blocked={409}
    )
    check(
        'late verified funds are retained in payment_exception without opening fulfillment',
        late_after['statusCode'] == 'payment_exception'
        and late_after['payments'][0]['statusCode'] == 'confirmed'
        and any(event['eventCode'] == 'late_payment_received' for event in late_after['timeline'])
        and sql(f"SELECT COUNT(*) FROM merchant_wallet_transaction WHERE reference_type_code='grading_order' AND reference_id={late_id}") == '0'
    )

    # A live attempt blocks another payment path. Once the attempt is terminal
    # and active_order_id is cleared, the unique per-order slot can be reused.
    terminal, _ = ready_order('terminal attempt slot')
    terminal_id = int(terminal['id'])
    terminal_path = f'/api/customer/orders/{terminal["orderNo"]}'
    terminal_payment_id = int(terminal['payments'][0]['id'])
    first_key = f'qa-active-a-{suffix}'
    second_key = f'qa-active-b-{suffix}'
    sql(f"""
        INSERT INTO payment_attempt
          (payment_record_id,order_id,active_order_id,customer_id,provider_code,idempotency_key,
           merchant_order_no,expected_amount,expected_currency,status_code)
        SELECT {terminal_payment_id},id,id,customer_id,'paypal','{first_key}',order_no,total_amount,currency_code,'creating'
        FROM grading_order WHERE id={terminal_id}
    """)
    customer(terminal_path + '/payment-proof', {
        'provider': 'bank_transfer', 'payerReference': f'QA-ACTIVE-{suffix}'
    }, blocked={409})
    sql(f"UPDATE payment_attempt SET status_code='failed',active_order_id=NULL WHERE idempotency_key='{first_key}'")
    sql(f"""
        INSERT INTO payment_attempt
          (payment_record_id,order_id,active_order_id,customer_id,provider_code,idempotency_key,
           merchant_order_no,expected_amount,expected_currency,status_code)
        SELECT {terminal_payment_id},id,id,customer_id,'paypal','{second_key}',order_no,total_amount,currency_code,'creating'
        FROM grading_order WHERE id={terminal_id}
    """)
    check('a terminal attempt releases the unique active checkout slot',
          sql(f"SELECT COUNT(*) FROM payment_attempt WHERE order_id={terminal_id} AND active_order_id={terminal_id}") == '1')
    sql(f"UPDATE payment_attempt SET status_code='failed',active_order_id=NULL WHERE idempotency_key='{second_key}'")
    terminal_paid_path = customer(terminal_path + '/payment-proof', {
        'provider': 'bank_transfer', 'payerReference': f'QA-RETRY-{suffix}'
    })
    check('a terminal gateway failure allows a controlled non-gateway retry',
          terminal_paid_path['statusCode'] == 'payment_review'
          and terminal_paid_path['payments'][0]['statusCode'] == 'proof_submitted')

    result = {
        'passed': len(qa.checks),
        'checks': qa.checks,
        'database': database,
        'realPayments': 0,
        'providerNetworkCalls': 0,
        'emailsSent': 0,
        'fixtureOrderNos': [
            bypass['orderNo'], legacy['orderNo'], frozen['orderNo'], proof['orderNo'],
            late['orderNo'], terminal['orderNo']
        ]
    }
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    result_path.chmod(0o600)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
