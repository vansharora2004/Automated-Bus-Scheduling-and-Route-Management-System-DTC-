// Load test for the DTC scheduling API, using the documented request mix.
//
// k6 rather than JMeter: the script is a file that reviews like code, and the thresholds below are the pass
// criteria rather than a number somebody reads off a graph afterwards.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=... ops/load/k6-request-mix.js
//
// Not run in CI. It needs a populated database and a deployed instance, and a load test that runs on every
// commit is a load test somebody disables.

import http from 'k6/http';
import { check, group, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  // Read-heavy, which is what this API actually is: operations screens poll, schedulers browse, and a run is
  // submitted a few times a day per depot.
  scenarios: {
    steady: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '2m', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '1m', target: 50 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  // The pass criteria. p95 under a second is the target from the architecture; the error rate is there because
  // a fast failure is still a failure.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:list}': ['p(95)<800'],
    'http_req_duration{kind:dashboard}': ['p(95)<500'],
    'http_req_duration{kind:report}': ['p(95)<1500'],
  },
};

export function setup() {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: __ENV.USERNAME, password: __ENV.PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(response, { 'login succeeded': (r) => r.status === 200 });
  return { token: response.json('accessToken') };
}

export default function (data) {
  const auth = { headers: { Authorization: `Bearer ${data.token}` } };

  // Roughly the observed mix: mostly list reads, a dashboard poll, and a report now and then.
  group('list reads', () => {
    for (const path of ['/api/v1/routes', '/api/v1/buses', '/api/v1/crew', '/api/v1/stops']) {
      const r = http.get(`${BASE_URL}${path}?size=50`, { ...auth, tags: { kind: 'list' } });
      check(r, { 'list ok': (res) => res.status === 200 });
    }
  });

  group('trip keyset page', () => {
    const r = http.get(`${BASE_URL}/api/v1/trips?limit=100`, { ...auth, tags: { kind: 'list' } });
    check(r, { 'trips ok': (res) => res.status === 200 });
  });

  group('dashboard', () => {
    const r = http.get(`${BASE_URL}/api/v1/dashboard/today`, { ...auth, tags: { kind: 'dashboard' } });
    check(r, { 'dashboard ok': (res) => res.status === 200 });
  });

  group('reports', () => {
    const r = http.get(`${BASE_URL}/api/v1/reports/fleet-utilization?size=50`, {
      ...auth,
      tags: { kind: 'report' },
    });
    check(r, { 'report ok': (res) => res.status === 200 });
  });

  sleep(1);
}
