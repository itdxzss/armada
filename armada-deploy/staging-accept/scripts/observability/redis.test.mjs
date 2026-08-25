import assert from 'node:assert/strict'
import test from 'node:test'

import { parseInfo, run } from './redis.mjs'

const evidence = ['--run-id', 'test-run', '--candidate-manifest-sha256', `sha256:${'a'.repeat(64)}`]

test('INFO parser exposes only the numeric allowlist', () => {
  const info = parseInfo(`
used_memory:100
used_memory_peak:120
connected_clients:3
blocked_clients:0
total_net_input_bytes:1000
total_net_output_bytes:2000
total_commands_processed:300
evicted_keys:0
keyspace_hits:90
keyspace_misses:10
redis_version:7.2.5
secret_token:must-not-appear
`)
  assert.equal(info.used_memory, 100)
  assert.equal('redis_version' in info, false)
  assert.equal('secret_token' in info, false)
})

test('fixture emits phase and logical label without a URL or host', async () => {
  const fixture = new URL('./fixtures/redis-info.txt', import.meta.url).pathname
  const result = await run([...evidence, '--phase', 'peak', '--fixture', `web=${fixture}`])
  assert.equal(result.status, 'COLLECTED')
  assert.equal(result.phase, 'peak')
  assert.equal(result.raw.sources[0].label, 'web')
  assert.equal(result.raw.sources[0].nodes[0].pingLatencyMs, 0)
  assert.equal(result.provenance, 'fixture')
  const serialized = JSON.stringify(result)
  assert.equal(serialized.includes('redis://'), false)
  assert.equal(serialized.includes('must-not-appear'), false)
})

test('missing local URL environment is BLOCKED and redacted', async () => {
  delete process.env.OBSERVABILITY_TEST_REDIS_URL
  const result = await run([...evidence, '--phase', 'start', '--source', 'web=OBSERVABILITY_TEST_REDIS_URL'])
  assert.equal(result.status, 'BLOCKED')
  assert.deepEqual(result.health.blockedReasons, ['REDIS_CONNECTION_ENV_MISSING'])
})
