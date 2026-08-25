import assert from 'node:assert/strict'
import test from 'node:test'

import { run, summarizeKafka } from './kafka.mjs'


const pairs = [{ topic: 'protocol.message.commands.v1', group: 'armada-api-message' }]
const evidence = ['--run-id', 'test-run', '--candidate-manifest-sha256', `sha256:${'a'.repeat(64)}`]
const topicOffsets = {
  'protocol.message.commands.v1': [
    { partition: 0, low: '100', high: '1200' },
    { partition: 1, low: '200', high: '2400' }
  ]
}
const groupOffsets = {
  'armada-api-message|protocol.message.commands.v1': [
    { partition: 0, offset: '1150' },
    { partition: 1, offset: '-1' }
  ]
}

async function withFakeBroker(operation) {
  const previous = process.env.KAFKA_BROKERS
  process.env.KAFKA_BROKERS = 'fixture.invalid:9092'
  try {
    return await operation()
  } finally {
    if (previous === undefined) delete process.env.KAFKA_BROKERS
    else process.env.KAFKA_BROKERS = previous
  }
}

function liveArgs(moduleUrl, ...extra) {
  return [
    ...evidence,
    '--phase', 'end',
    '--pair', 'runtime.topic=runtime-group',
    '--module', moduleUrl,
    ...extra
  ]
}

test('summarizes lag without joining or committing a consumer group', () => {
  const result = summarizeKafka(pairs, topicOffsets, groupOffsets)
  assert.equal(result.groups[0].totalLag, 2250)
  assert.equal(result.groups[0].maxLag, 2200)
  assert.equal(result.groups[0].uninitializedPartitions, 1)
  assert.deepEqual(result.partitions[1], {
    topic: 'protocol.message.commands.v1',
    group: 'armada-api-message',
    partition: 1,
    lowOffset: 200,
    highOffset: 2400,
    committedOffset: -1,
    effectiveCommittedOffset: 200,
    lag: 2200,
    uninitialized: true,
    truncated: false
  })
})

test('fixture CLI contract emits start/peak/end raw offsets', async () => {
  const fixture = new URL('./fixtures/kafka-offsets.json', import.meta.url).pathname
  const result = await run([
    ...evidence,
    '--phase', 'peak',
    '--pair', 'protocol.message.commands.v1=armada-api-message',
    '--fixture', fixture
  ])
  assert.equal(result.status, 'COLLECTED')
  assert.equal(result.phase, 'peak')
  assert.equal(result.raw.partitions.length, 2)
  assert.equal(result.provenance, 'fixture')
})

test('retains committed-below-low as explicit truncation evidence', () => {
  const result = summarizeKafka(pairs, topicOffsets, {
    'armada-api-message|protocol.message.commands.v1': [
      { partition: 0, offset: '99' },
      { partition: 1, offset: '200' }
    ]
  })
  assert.equal(result.groups[0].truncatedPartitions, 1)
  assert.equal(result.partitions[0].truncated, true)
  assert.equal(result.partitions[0].committedOffset, 99)
})

test('committed above high is a contract error instead of negative lag', () => {
  assert.throws(
    () => summarizeKafka(pairs, topicOffsets, {
      'armada-api-message|protocol.message.commands.v1': [
        { partition: 0, offset: '1201' },
        { partition: 1, offset: '200' }
      ]
    }),
    /KAFKA_COMMITTED_ABOVE_HIGH/
  )
})

test('missing group partitions are BLOCKED instead of reported as zero lag', () => {
  assert.throws(
    () => summarizeKafka(pairs, topicOffsets, {
      'armada-api-message|protocol.message.commands.v1': [{ partition: 0, offset: '1150' }]
    }),
    /KAFKA_PARTITION_CONTRACT_FAILED/
  )
})

test('KafkaJS 2.2.4 admin contract succeeds with an explicit runtime pair', async () => {
  const moduleUrl = new URL('./fixtures/kafkajs-fake.mjs', import.meta.url).href
  const result = await withFakeBroker(() => run(liveArgs(moduleUrl)))
  assert.equal(result.status, 'COLLECTED')
  assert.equal(result.provenance, 'live')
  assert.equal(result.raw.groups[0].totalLag, 0)
})

test('live query failure exposes only a redacted topic-step classification', async () => {
  const moduleUrl = new URL('./fixtures/kafkajs-fake.mjs?failure=topic', import.meta.url).href
  const result = await withFakeBroker(() => run(liveArgs(moduleUrl)))
  assert.equal(result.status, 'BLOCKED')
  const failed = result.health.checks.find(check => check.ok === false)
  assert.deepEqual(failed.diagnostic, {
    step: 'topic',
    error: { name: 'KafkaJSProtocolError', type: 'UNKNOWN_TOPIC_OR_PARTITION' }
  })
  const serialized = JSON.stringify(result)
  assert.equal(serialized.includes('must-not-appear'), false)
  assert.equal(serialized.includes('secret.example'), false)
  assert.equal(serialized.includes('broker'), false)
})

test('group lookup failure remains distinct from topic lookup failure', async () => {
  const moduleUrl = new URL('./fixtures/kafkajs-fake.mjs?failure=group', import.meta.url).href
  const result = await withFakeBroker(() => run(liveArgs(moduleUrl)))
  const failed = result.health.checks.find(check => check.ok === false)
  assert.equal(result.status, 'BLOCKED')
  assert.equal(failed.reason, 'KAFKA_GROUP_FAILED')
  assert.equal(failed.diagnostic.step, 'group')
})

test('known KafkaJS 2.2.4 negative timer warning is suppressed narrowly', async () => {
  const moduleUrl = new URL('./fixtures/kafkajs-fake.mjs?failure=negative-timeout-warning', import.meta.url).href
  const warnings = []
  const onWarning = warning => warnings.push(warning.name)
  process.on('warning', onWarning)
  try {
    const result = await withFakeBroker(() => run(liveArgs(moduleUrl)))
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(result.status, 'COLLECTED')
    assert.deepEqual(warnings, [])
  } finally {
    process.off('warning', onWarning)
  }
})

test('a hung live step is bounded and fail-closed', async () => {
  const moduleUrl = new URL('./fixtures/kafkajs-fake.mjs?failure=connect-timeout', import.meta.url).href
  const startedAt = Date.now()
  const result = await withFakeBroker(() => run(liveArgs(moduleUrl, '--step-timeout-ms', '1000')))
  const elapsed = Date.now() - startedAt
  assert.equal(result.status, 'BLOCKED')
  assert.ok(elapsed >= 900 && elapsed < 2500, `unexpected deadline duration ${elapsed}ms`)
  const failed = result.health.checks.find(check => check.ok === false)
  assert.deepEqual(failed.diagnostic, {
    step: 'connect',
    error: { name: 'DeadlineExceededError', type: 'DEADLINE_EXCEEDED' }
  })
})
