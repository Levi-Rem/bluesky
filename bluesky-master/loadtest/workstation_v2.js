import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const REST_P95_MS = Number(__ENV.BS_REST_P95_MS || 500);
const STABILITY = (__ENV.BS_STABILITY || 'false').toLowerCase() === 'true';
const STEADY_DURATION = __ENV.BS_STEADY_DURATION || (STABILITY ? '7h50m' : '30m');
const WARMUP_DURATION = __ENV.BS_WARMUP_DURATION || '10m';
const OUTPUT = __ENV.BS_K6_SUMMARY || 'loadtest/artifacts/k6-summary.json';

const successfulLatency = new Trend('business_success_latency', true);
const serverErrorRate = new Rate('business_5xx_rate');
const clientInputResults = new Counter('business_4xx_count');
const successfulSamples = new Counter('business_success_samples');
const writeMixCounter = {
  HDG: new Counter('write_mix_hdg'),
  ALT: new Counter('write_mix_alt'),
  SPD: new Counter('write_mix_spd'),
  RTE: new Counter('write_mix_rte'),
  HANDOVER: new Counter('write_mix_handover'),
};

export const options = {
  discardResponseBodies: false,
  scenarios: {
    warmup_reads: {
      executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: WARMUP_DURATION,
      preAllocatedVUs: 32, maxVUs: 160, exec: 'runReadMix', tags: { phase: 'warmup' },
    },
    warmup_writes: {
      executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: WARMUP_DURATION,
      preAllocatedVUs: 16, maxVUs: 80, exec: 'runWriteMix', tags: { phase: 'warmup' },
    },
    steady_reads: {
      executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', startTime: WARMUP_DURATION,
      duration: STEADY_DURATION, preAllocatedVUs: 32, maxVUs: 160,
      exec: 'runReadMix', tags: { phase: 'steady' },
    },
    steady_writes: {
      executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', startTime: WARMUP_DURATION,
      duration: STEADY_DURATION, preAllocatedVUs: 16, maxVUs: 80,
      exec: 'runWriteMix', tags: { phase: 'steady' },
    },
  },
  thresholds: {
    'business_success_latency{phase:steady}': [`p(95)<${REST_P95_MS}`],
    'business_5xx_rate{phase:steady}': ['rate<0.001'],
    'business_success_samples{phase:steady}': ['count>=10000'],
    'dropped_iterations{phase:steady}': ['count==0'],
  },
};

function parseConfig() {
  if (!__ENV.BS_LOAD_CONFIG_JSON) {
    throw new Error('BS_LOAD_CONFIG_JSON 未设置；必须传入脱敏后的负载目标 JSON');
  }
  const config = JSON.parse(__ENV.BS_LOAD_CONFIG_JSON);
  if (!config.baseUrl || !Array.isArray(config.targets) || config.targets.length !== 8) {
    throw new Error('负载配置必须包含 baseUrl 和恰好 8 个训练组 targets');
  }
  for (const target of config.targets) {
    if (!target.groupId || !target.terminalId || !target.fingerprintDigest ||
        !Array.isArray(target.aircraftIds) || target.aircraftIds.length < 200) {
      throw new Error(`训练组 ${target.groupId || '<unknown>'} 未配置 200 架航空器或终端身份`);
    }
  }
  return config;
}

function headers(config, target, write) {
  const values = {
    Accept: 'application/json',
    'X-Gateway-Secret': __ENV.BS_ACCEPTANCE_GATEWAY_SECRET || '',
    'X-Trusted-Caller-Type': 'TERMINAL',
    'X-Trusted-Caller-Id': target.terminalId,
    'X-Trusted-Terminal-Id': target.terminalId,
    'X-Trusted-Fingerprint-Digest': target.fingerprintDigest,
    'X-Request-Id': `k6-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`,
  };
  if (write) {
    values['Content-Type'] = 'application/json';
    values['Idempotency-Key'] = `k6-${exec.scenario.name}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`;
  }
  return values;
}

function selectTarget(config) {
  return config.targets[exec.scenario.iterationInTest % config.targets.length];
}

function record(response, operation) {
  const phase = exec.scenario.name.startsWith('steady') ? 'steady' : 'warmup';
  const tags = { phase, operation };
  if (response.status >= 200 && response.status < 300) {
    successfulLatency.add(response.timings.duration, tags);
    successfulSamples.add(1, tags);
    serverErrorRate.add(false, tags);
  } else if (response.status >= 500) {
    serverErrorRate.add(true, tags);
  } else if (response.status >= 400) {
    clientInputResults.add(1, tags);
    serverErrorRate.add(false, tags);
  }
  check(response, {
    [`${operation} 无 5xx`]: (result) => result.status < 500,
  }, tags);
}

export function setupGroups() {
  const config = parseConfig();
  if (!__ENV.BS_ACCEPTANCE_GATEWAY_SECRET) {
    throw new Error('BS_ACCEPTANCE_GATEWAY_SECRET 未设置');
  }
  for (const target of config.targets) {
    const response = http.get(
      `${config.baseUrl}/api/v2/workstations/${target.terminalId}/bootstrap`,
      { headers: headers(config, target, false), tags: { phase: 'setup', operation: 'bootstrap' } },
    );
    if (response.status !== 200) {
      throw new Error(`训练组 ${target.groupId} bootstrap 失败: ${response.status} ${response.body}`);
    }
  }
  return config;
}

export function setup() {
  return setupGroups();
}

export function runReadMix(config) {
  const target = selectTarget(config);
  const aircraftId = target.aircraftIds[
    exec.scenario.iterationInTest % target.aircraftIds.length
  ];
  const selector = exec.scenario.iterationInTest % 4;
  let response;
  let operation;
  if (selector === 0) {
    operation = 'bootstrap';
    response = http.get(`${config.baseUrl}/api/v2/workstations/${target.terminalId}/bootstrap`,
      { headers: headers(config, target, false), tags: { operation } });
  } else if (selector === 1) {
    operation = 'aircraft';
    response = http.get(`${config.baseUrl}/api/v2/aircraft/${aircraftId}`,
      { headers: headers(config, target, false), tags: { operation } });
  } else if (selector === 2) {
    operation = 'instructions';
    response = http.get(`${config.baseUrl}/api/v2/aircraft/${aircraftId}/instructions`,
      { headers: headers(config, target, false), tags: { operation } });
  } else {
    operation = 'assignments';
    response = http.get(`${config.baseUrl}/api/v2/exercise-groups/${target.groupId}/assignments`,
      { headers: headers(config, target, false), tags: { operation } });
  }
  record(response, operation);
}

function commandBody(target, type) {
  const values = {
    HDG: { type: 'HDG', parameters: { headingDegrees: 90 } },
    ALT: { type: 'ALT', parameters: { altitudeFtMsl: 12000 } },
    SPD: { type: 'SPD', parameters: { indicatedAirspeedKt: 250 } },
    RTE: { type: 'RTE', parameters: { route: target.route || [] } },
  };
  return JSON.stringify({
    aircraftRevision: target.aircraftRevision || 1,
    scheduling: 'REPLACE',
    command: values[type],
  });
}

export function runWriteMix(config) {
  const target = selectTarget(config);
  const aircraftId = target.aircraftIds[
    exec.scenario.iterationInTest % target.aircraftIds.length
  ];
  const bucket = exec.scenario.iterationInTest % 10;
  const operation = bucket < 4 ? 'HDG' : bucket < 6 ? 'ALT' : bucket < 8 ? 'SPD' :
    bucket === 8 ? 'RTE' : 'HANDOVER';
  writeMixCounter[operation].add(1);
  let response;
  if (operation === 'HANDOVER') {
    response = http.post(`${config.baseUrl}/api/v2/aircraft/${aircraftId}/handover`,
      JSON.stringify({
        aircraftRevision: target.aircraftRevision || 1,
        targetFrequencyMhz: target.handoverFrequencyMhz,
      }), { headers: headers(config, target, true), tags: { operation } });
  } else {
    response = http.post(`${config.baseUrl}/api/v2/aircraft/${aircraftId}/instructions`,
      commandBody(target, operation),
      { headers: headers(config, target, true), tags: { operation } });
  }
  record(response, operation);
}

// 断网/重连由 SSE companion 和受控故障调度器执行；这里保留设计规定的稳定入口。
export function disconnectRandomClients() {
  sleep(Number(__ENV.BS_DISCONNECT_SECONDS || 3));
}

export function summarize(data) {
  return {
    stdout: JSON.stringify({
      type: 'AT-12-k6-summary',
      steadyDuration: STEADY_DURATION,
      stabilityMode: STABILITY,
      metrics: data.metrics,
    }, null, 2),
    [OUTPUT]: JSON.stringify(data, null, 2),
  };
}

export function handleSummary(data) {
  return summarize(data);
}
