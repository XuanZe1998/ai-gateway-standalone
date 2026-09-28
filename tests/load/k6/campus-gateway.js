import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('gateway_errors');
const modelLatency = new Trend('model_latency', true);

export const options = {
  discardResponseBodies: true,
  scenarios: {
    browse_models: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      stages: [
        { target: Number(__ENV.TARGET_RPS || 100), duration: '2m' },
        { target: Number(__ENV.TARGET_RPS || 100), duration: __ENV.HOLD_DURATION || '10m' },
        { target: 0, duration: '2m' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    gateway_errors: ['rate<0.01'],
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    model_latency: ['p(95)<2000'],
  },
};

const baseUrl = (__ENV.BASE_URL || 'http://localhost:38008').replace(/\/$/, '');
const apiKey = __ENV.API_KEY || '';

export default function () {
  const headers = apiKey ? { 'X-API-Key': apiKey } : {};
  const response = http.get(`${baseUrl}/v1/models`, { headers, tags: { endpoint: 'models' } });
  const ok = check(response, {
    'models status is 200': (res) => res.status === 200,
  });
  errorRate.add(!ok);
  modelLatency.add(response.timings.duration);
  sleep(0.1);
}

export function setup() {
  const response = http.get(`${baseUrl}/actuator/health/readiness`);
  if (response.status !== 200) {
    throw new Error(`Gateway is not ready: HTTP ${response.status}`);
  }
}
