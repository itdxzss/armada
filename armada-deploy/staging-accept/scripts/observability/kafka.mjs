#!/usr/bin/env node

import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

const SCHEMA_VERSION = 1
const SAFE_LABEL = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,248}$/
const SAFE_RUN_ID = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/
const EVIDENCE_SHA256 = /^sha256:[0-9a-fA-F]{64}$/
const SAFE_ERROR_IDENTIFIER = /^[A-Za-z][A-Za-z0-9_.-]{0,79}$/
const DEFAULT_STEP_TIMEOUT_MS = 15000
const MIN_STEP_TIMEOUT_MS = 1000
const MAX_STEP_TIMEOUT_MS = 120000

class CollectionError extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

class KafkaStepError extends CollectionError {
  constructor(step, error, deadline = false) {
    super(`KAFKA_${step.toUpperCase()}_${deadline ? 'DEADLINE_EXCEEDED' : 'FAILED'}`)
    this.step = step
    const classified = classifyError(error)
    this.errorName = deadline ? 'DeadlineExceededError' : classified.name
    this.errorType = deadline ? 'DEADLINE_EXCEEDED' : classified.type
  }
}

function baseResult(options) {
  return {
    schemaVersion: SCHEMA_VERSION,
    collector: 'kafka',
    environment: options.environment,
    phase: options.phase,
    runId: options.runId,
    candidateManifestSha256: options.candidateManifestSha256.toLowerCase(),
    provenance: options.fixture ? 'fixture' : 'live',
    observedAt: new Date().toISOString(),
    status: 'COLLECTED',
    health: { ok: true, checks: [], blockedReasons: [] },
    semantics: {
      offsets: 'read-only Kafka high/low and committed offsets; the collector never joins a group or commits',
      lag: 'max(0, highOffset - effectiveCommittedOffset) per partition',
      uninitialized: 'committedOffset=-1 uses lowOffset as the effective baseline and remains explicitly flagged',
      truncation: 'committedOffset below lowOffset is retained as truncated=true for evaluator FAIL',
      counters: 'raw offsets are retained for start/peak/end comparison',
      deadline: 'connect, topic, group and disconnect calls have independent bounded deadlines'
    },
    raw: { partitions: [], groups: [] }
  }
}

function addCheck(result, name, ok, reason = '', diagnostic = null) {
  const check = { name, ok }
  if (!ok) {
    check.reason = reason
    if (diagnostic) check.diagnostic = diagnostic
    result.status = 'BLOCKED'
    result.health.ok = false
    if (reason && !result.health.blockedReasons.includes(reason)) {
      result.health.blockedReasons.push(reason)
    }
  }
  result.health.checks.push(check)
}

function parseArgs(argv) {
  const options = {
    environment: 'test1',
    phase: '',
    pairs: [],
    fixture: '',
    runId: '',
    candidateManifestSha256: '',
    stepTimeoutMs: DEFAULT_STEP_TIMEOUT_MS,
    module: process.env.KAFKAJS_MODULE || 'kafkajs'
  }
  for (let index = 0; index < argv.length; index += 1) {
    const name = argv[index]
    const value = argv[index + 1]
    if (name === '--environment' || name === '--phase' || name === '--pair' || name === '--fixture' || name === '--module' || name === '--run-id' || name === '--candidate-manifest-sha256' || name === '--step-timeout-ms') {
      if (value === undefined || value.startsWith('--')) throw new CollectionError('INVALID_ARGUMENT')
      index += 1
      if (name === '--environment') options.environment = value
      else if (name === '--phase') options.phase = value
      else if (name === '--pair') options.pairs.push(parsePair(value))
      else if (name === '--fixture') options.fixture = value
      else if (name === '--run-id') options.runId = value
      else if (name === '--candidate-manifest-sha256') options.candidateManifestSha256 = value
      else if (name === '--step-timeout-ms') options.stepTimeoutMs = Number(value)
      else options.module = value
      continue
    }
    throw new CollectionError('INVALID_ARGUMENT')
  }
  if (!/^[a-z][a-z0-9-]{0,63}$/.test(options.environment) || !['start', 'peak', 'end'].includes(options.phase)) {
    throw new CollectionError('INVALID_ARGUMENT')
  }
  if (!SAFE_RUN_ID.test(options.runId) || !EVIDENCE_SHA256.test(options.candidateManifestSha256)) {
    throw new CollectionError('INVALID_EVIDENCE_IDENTITY')
  }
  if (!Number.isInteger(options.stepTimeoutMs) || options.stepTimeoutMs < MIN_STEP_TIMEOUT_MS || options.stepTimeoutMs > MAX_STEP_TIMEOUT_MS) {
    throw new CollectionError('INVALID_STEP_TIMEOUT')
  }
  if (options.pairs.length === 0) throw new CollectionError('KAFKA_PAIRS_MISSING')
  const identities = new Set()
  for (const pair of options.pairs) {
    const identity = `${pair.group}\u0000${pair.topic}`
    if (identities.has(identity)) throw new CollectionError('DUPLICATE_KAFKA_PAIR')
    identities.add(identity)
  }
  return options
}

function parsePair(raw) {
  const separator = raw.indexOf('=')
  const topic = raw.slice(0, separator)
  const group = raw.slice(separator + 1)
  if (separator <= 0 || !SAFE_LABEL.test(topic) || !SAFE_LABEL.test(group)) {
    throw new CollectionError('INVALID_KAFKA_PAIR')
  }
  return { topic, group }
}

function asOffset(raw, code) {
  const value = typeof raw === 'bigint' ? raw : BigInt(String(raw))
  if (value < -1n) throw new CollectionError(code)
  return value
}

function safeOffset(value, code) {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new CollectionError(code)
  return Number(value)
}

function safeErrorIdentifier(value, fallback) {
  return typeof value === 'string' && SAFE_ERROR_IDENTIFIER.test(value) ? value : fallback
}

function classifyError(error) {
  let current = error
  let name = 'UnknownError'
  let type = 'UNCLASSIFIED'
  for (let depth = 0; depth < 3 && current && typeof current === 'object'; depth += 1) {
    if (name === 'UnknownError') name = safeErrorIdentifier(current.name, name)
    if (type === 'UNCLASSIFIED') {
      type = safeErrorIdentifier(current.type, type)
      if (type === 'UNCLASSIFIED') type = safeErrorIdentifier(current.code, type)
    }
    current = current.cause
  }
  return { name, type }
}

function stepDiagnostic(error) {
  if (!(error instanceof KafkaStepError)) return null
  return {
    step: error.step,
    error: { name: error.errorName, type: error.errorType }
  }
}

async function withDeadline(step, timeoutMs, operation) {
  let timeoutId
  const deadline = new Promise((resolve, reject) => {
    timeoutId = setTimeout(
      () => reject(new KafkaStepError(step, null, true)),
      timeoutMs
    )
  })
  try {
    return await Promise.race([Promise.resolve().then(operation), deadline])
  } catch (error) {
    if (error instanceof KafkaStepError) throw error
    throw new KafkaStepError(step, error)
  } finally {
    clearTimeout(timeoutId)
  }
}

function installKafkaJsNegativeTimeoutWarningFilter() {
  const original = process.emitWarning
  process.emitWarning = function filteredKafkaJsWarning(warning, ...args) {
    const message = typeof warning === 'string' ? warning : warning?.message
    const warningType = typeof args[0] === 'string' ? args[0] : args[0]?.type
    if (
      warningType === 'TimeoutNegativeWarning' &&
      typeof message === 'string' &&
      /^-\d+ is a negative number\.\nTimeout duration was set to 1\.$/.test(message)
    ) {
      return
    }
    return original.call(process, warning, ...args)
  }
  return () => {
    process.emitWarning = original
  }
}

export function summarizeKafka(pairs, topicOffsets, groupOffsets) {
  const partitions = []
  const groups = []
  for (const pair of pairs) {
    const latestRows = topicOffsets[pair.topic]
    const committedRows = groupOffsets[`${pair.group}|${pair.topic}`]
    if (!Array.isArray(latestRows) || !Array.isArray(committedRows) || latestRows.length === 0) {
      throw new CollectionError('KAFKA_OFFSETS_INCOMPLETE')
    }
    const committedByPartition = new Map()
    for (const row of committedRows) {
      const partition = Number(row.partition)
      if (!Number.isInteger(partition) || partition < 0 || committedByPartition.has(partition)) {
        throw new CollectionError('KAFKA_OFFSETS_INVALID')
      }
      committedByPartition.set(partition, asOffset(row.offset, 'KAFKA_OFFSETS_INVALID'))
    }
    let totalLag = 0
    let maxLag = 0
    let uninitializedPartitions = 0
    let truncatedPartitions = 0
    const seen = new Set()
    for (const row of latestRows) {
      const partition = Number(row.partition)
      if (!Number.isInteger(partition) || partition < 0 || seen.has(partition) || !committedByPartition.has(partition)) {
        throw new CollectionError('KAFKA_PARTITION_CONTRACT_FAILED')
      }
      seen.add(partition)
      const high = asOffset(row.high ?? row.offset, 'KAFKA_OFFSETS_INVALID')
      const low = asOffset(row.low ?? 0, 'KAFKA_OFFSETS_INVALID')
      const committed = committedByPartition.get(partition)
      if (high < 0n || low < 0n || high < low) throw new CollectionError('KAFKA_OFFSETS_INVALID')
      const uninitialized = committed === -1n
      if (!uninitialized && committed > high) throw new CollectionError('KAFKA_COMMITTED_ABOVE_HIGH')
      const truncated = !uninitialized && committed < low
      const effective = uninitialized || truncated ? low : committed
      const lag = high > effective ? high - effective : 0n
      const numericLag = safeOffset(lag, 'KAFKA_OFFSET_TOO_LARGE')
      totalLag += numericLag
      maxLag = Math.max(maxLag, numericLag)
      if (uninitialized) uninitializedPartitions += 1
      if (truncated) truncatedPartitions += 1
      partitions.push({
        topic: pair.topic,
        group: pair.group,
        partition,
        lowOffset: safeOffset(low, 'KAFKA_OFFSET_TOO_LARGE'),
        highOffset: safeOffset(high, 'KAFKA_OFFSET_TOO_LARGE'),
        committedOffset: safeOffset(committed, 'KAFKA_OFFSET_TOO_LARGE'),
        effectiveCommittedOffset: safeOffset(effective, 'KAFKA_OFFSET_TOO_LARGE'),
        lag: numericLag,
        uninitialized,
        truncated
      })
    }
    if (seen.size !== committedByPartition.size) throw new CollectionError('KAFKA_PARTITION_CONTRACT_FAILED')
    groups.push({
      topic: pair.topic,
      group: pair.group,
      partitions: seen.size,
      totalLag,
      maxLag,
      uninitializedPartitions,
      truncatedPartitions
    })
  }
  partitions.sort((left, right) => left.group.localeCompare(right.group) || left.topic.localeCompare(right.topic) || left.partition - right.partition)
  groups.sort((left, right) => left.group.localeCompare(right.group) || left.topic.localeCompare(right.topic))
  return { partitions, groups }
}

async function fixtureSnapshot(path) {
  try {
    const payload = JSON.parse(await fs.readFile(path === '-' ? 0 : path, 'utf8'))
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
      throw new CollectionError('KAFKA_FIXTURE_INVALID')
    }
    return payload
  } catch (error) {
    if (error instanceof CollectionError) throw error
    throw new CollectionError('KAFKA_FIXTURE_UNAVAILABLE')
  }
}

function csv(raw) {
  return String(raw || '').split(',').map(value => value.trim()).filter(Boolean)
}

async function liveSnapshot(options) {
  const brokers = csv(process.env.KAFKA_BROKERS)
  if (brokers.length === 0) throw new CollectionError('KAFKA_CONNECTION_ENV_MISSING')
  let imported
  try {
    let moduleSpecifier = options.module
    if (!moduleSpecifier.includes('/') && !moduleSpecifier.startsWith('file:')) {
      const requireFromWorkingDirectory = createRequire(pathToFileURL(`${process.cwd()}/package.json`))
      moduleSpecifier = pathToFileURL(requireFromWorkingDirectory.resolve(moduleSpecifier)).href
    } else if (moduleSpecifier.startsWith('/')) {
      moduleSpecifier = pathToFileURL(moduleSpecifier).href
    }
    imported = await import(moduleSpecifier)
  } catch {
    throw new CollectionError('KAFKAJS_UNAVAILABLE')
  }
  const kafkaJs = imported.default || imported
  const requestTimeout = Math.max(1000, Math.min(10000, Math.floor(options.stepTimeoutMs / 3)))
  const config = {
    clientId: 'staging-accept-observability',
    brokers,
    logLevel: kafkaJs.logLevel.NOTHING,
    connectionTimeout: Math.min(5000, requestTimeout),
    authenticationTimeout: requestTimeout,
    requestTimeout,
    enforceRequestTimeout: true,
    retry: { retries: 1, initialRetryTime: 100, maxRetryTime: 500 }
  }
  if (/^(1|true|yes)$/i.test(process.env.KAFKA_SSL || '')) config.ssl = true
  if (process.env.KAFKA_USERNAME || process.env.KAFKA_PASSWORD) {
    config.sasl = {
      mechanism: process.env.KAFKA_SASL_MECHANISM || 'plain',
      username: process.env.KAFKA_USERNAME || '',
      password: process.env.KAFKA_PASSWORD || ''
    }
  }
  let admin
  try {
    const kafka = new kafkaJs.Kafka(config)
    admin = kafka.admin()
  } catch (error) {
    throw new KafkaStepError('connect', error)
  }
  const topicOffsets = {}
  const groupOffsets = {}
  let failure = null
  const restoreWarningFilter = installKafkaJsNegativeTimeoutWarningFilter()
  try {
    await withDeadline('connect', options.stepTimeoutMs, () => admin.connect())
    for (const topic of [...new Set(options.pairs.map(pair => pair.topic))]) {
      const rows = await withDeadline(
        'topic',
        options.stepTimeoutMs,
        () => admin.fetchTopicOffsets(topic)
      )
      if (!Array.isArray(rows) || rows.length === 0) {
        throw new KafkaStepError('topic', new CollectionError('KAFKA_OFFSETS_INCOMPLETE'))
      }
      topicOffsets[topic] = rows
    }
    for (const pair of options.pairs) {
      const rows = await withDeadline(
        'group',
        options.stepTimeoutMs,
        () => admin.fetchOffsets({ groupId: pair.group, topics: [pair.topic] })
      )
      if (!Array.isArray(rows)) {
        throw new KafkaStepError('group', new CollectionError('KAFKA_OFFSETS_INCOMPLETE'))
      }
      const topic = rows.find(row => row.topic === pair.topic)
      if (!topic || !Array.isArray(topic.partitions)) {
        throw new KafkaStepError('group', new CollectionError('KAFKA_OFFSETS_INCOMPLETE'))
      }
      groupOffsets[`${pair.group}|${pair.topic}`] = topic.partitions
    }
  } catch (error) {
    failure = error instanceof CollectionError
      ? error
      : new KafkaStepError('connect', error)
  } finally {
    try {
      await withDeadline('disconnect', options.stepTimeoutMs, () => admin.disconnect())
    } catch (error) {
      if (failure === null) failure = error
    }
    restoreWarningFilter()
  }
  if (failure !== null) throw failure
  return { topicOffsets, groupOffsets }
}

export async function run(argv) {
  let options
  try {
    options = parseArgs(argv)
  } catch (error) {
    const fallback = {
      environment: 'test1', phase: 'start', runId: 'invalid',
      candidateManifestSha256: `sha256:${'0'.repeat(64)}`, fixture: ''
    }
    const result = baseResult(fallback)
    addCheck(result, 'kafka-setup', false, error instanceof CollectionError ? error.code : 'INVALID_ARGUMENT')
    return result
  }
  const result = baseResult(options)
  try {
    const snapshot = options.fixture ? await fixtureSnapshot(options.fixture) : await liveSnapshot(options)
    result.raw = summarizeKafka(options.pairs, snapshot.topicOffsets, snapshot.groupOffsets)
    addCheck(result, 'kafka-offsets', true)
  } catch (error) {
    const reason = error instanceof CollectionError ? error.code : 'KAFKA_QUERY_FAILED'
    addCheck(result, 'kafka-offsets', false, reason, stepDiagnostic(error))
  }
  return result
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMain) {
  const result = await run(process.argv.slice(2))
  process.stdout.write(`${JSON.stringify(result)}\n`)
  process.exitCode = result.status === 'COLLECTED' ? 0 : 2
}
