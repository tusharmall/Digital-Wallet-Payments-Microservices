import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const failures = new Counter('transfer_failures');
const baseUrl = __ENV.BASE_URL || 'http://localhost:8081';
const userId = __ENV.USER_ID;
const fromWalletId = __ENV.FROM_WALLET_ID;
const toWalletId = __ENV.TO_WALLET_ID;

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'], // A demo target, not a claim that the service already meets it.
    transfer_failures: ['count<1000'],
  },
};

export default function () {
  if (!userId || !fromWalletId || !toWalletId) {
    throw new Error('Set USER_ID, FROM_WALLET_ID and TO_WALLET_ID.');
  }
  const idempotencyKey = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const payload = JSON.stringify({
    fromWalletId: Number(fromWalletId),
    toWalletId: Number(toWalletId),
    amount: Number(__ENV.AMOUNT || '1.00'),
  });
  const response = http.post(`${baseUrl}/api/transfers`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': String(userId),
      'Idempotency-Key': idempotencyKey,
    },
  });
  const accepted = check(response, {
    'transfer returned a successful status': (r) => r.status === 200,
  });
  if (!accepted) failures.add(1);
  sleep(0.1);
}
