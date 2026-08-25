#!/usr/bin/env node

import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

const INFO_FIELDS = [
  'used_memory', 'used_memory_rss', 'used_memory_peak', 'maxmemory',
  'connected_clients', 'blocked_clients', 'instantaneous_ops_per_sec',
  'total_net_input_bytes', 'total_net_output_bytes', 'total_commands_processed',
  'evicted_keys', 'expired_keys', 'keyspace_hits', 'keyspace_misses', 'rejected_connections'
]
const REQUIRED_FIELDS = [
  'used_memory', 'used_memory_peak', 'connected_clients', 'blocked_clients',
  'total_net_input_bytes', 'total_net_output_bytes', 'total_commands_processed',
  'evicted_keys', 'keyspace_hits', 'keyspace_misses'
]
const CLUSTER_FIELDS = [
  'cluster_state', 'cluster_slots_assigned', 'cluster_slots_ok', 'cluster_slots_pfail',
  'cluster_slots_fail', 'cluster_known_nodes', 'cluster_size'
]
const SAFE_LABEL = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/
const SAFE_ENV = /^[A-Z][A-Z0-9_]{0,127}$/
const SAFE_RUN_ID = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/
const EVIDENCE_SHA256 = /^sha256:[0-9a-fA-F]{64}$/

class CollectionError extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

function baseResult(options) {
  return {
    schemaVersion: 1,
    collector: 'redis',
    environment: options.environment,
    phase: options.phase,
    runId: options.runId,
    candidateManifestSha256: options.candidateManifestSha256.toLowerCase(),
    provenance: options.fixtures.length > 0 ? 'fixture' : 'live',
    observedAt: new Date().toISOString(),
    status: 'COLLECTED',
    health: { ok: true, checks: [], blockedReasons: [] },
    semantics: {
      memory: 'Redis INFO process counters',
      network: 'Redis protocol bytes, not EC2/VPC/proxy/cloud billing',
      cloudBilling: false,
      privacy: 'connection URL, host, user, password and keys are never emitted',
      counters: 'cumulative counters retained for start/peak/end comparison'
    },
    raw: { sources: [] }
  }
}

function addCheck(result, name, ok, reason = '') {
  const check = { name, ok }
  if (!ok) {
    check.reason = reason
    result.status = 'BLOCKED'
    result.health.ok = false
    if (reason && !result.health.blockedReasons.includes(reason)) result.health.blockedReasons.push(reason)
  }
  result.health.checks.push(check)
}

function mapping(raw, kind) {
  const separator = raw.indexOf('=')
  const label = raw.slice(0, separator)
  const value = raw.slice(separator + 1)
  if (separator <= 0 || !SAFE_LABEL.test(label) || !value) throw new CollectionError(`INVALID_${kind}_MAPPING`)
  return { label, value }
}

function parseArgs(argv) {
  const options = {
    environment: 'test1', phase: '', sources: [], fixtures: [], runId: '',
    candidateManifestSha256: '',
    module: process.env.IOREDIS_MODULE || 'ioredis'
  }
  for (let index = 0; index < argv.length; index += 1) {
    const name = argv[index]
    const value = argv[index + 1]
    if (!['--environment', '--phase', '--source', '--fixture', '--module', '--run-id', '--candidate-manifest-sha256'].includes(name) ||
        value === undefined || value.startsWith('--')) {
      throw new CollectionError('INVALID_ARGUMENT')
    }
    index += 1
    if (name === '--environment') options.environment = value
    else if (name === '--phase') options.phase = value
    else if (name === '--source') options.sources.push(mapping(value, 'SOURCE'))
    else if (name === '--fixture') options.fixtures.push(mapping(value, 'FIXTURE'))
    else if (name === '--run-id') options.runId = value
    else if (name === '--candidate-manifest-sha256') options.candidateManifestSha256 = value
    else options.module = value
  }
  if (!/^[a-z][a-z0-9-]{0,63}$/.test(options.environment) || !['start', 'peak', 'end'].includes(options.phase)) {
    throw new CollectionError('INVALID_ARGUMENT')
  }
  if (!SAFE_RUN_ID.test(options.runId) || !EVIDENCE_SHA256.test(options.candidateManifestSha256)) {
    throw new CollectionError('INVALID_EVIDENCE_IDENTITY')
  }
  if (options.sources.length === 0 && options.fixtures.length === 0) throw new CollectionError('REDIS_SOURCES_MISSING')
  const labels = new Set()
  for (const source of options.sources) {
    if (!SAFE_ENV.test(source.value) || labels.has(source.label)) throw new CollectionError('INVALID_REDIS_SOURCE')
    labels.add(source.label)
  }
  for (const fixture of options.fixtures) {
    if (labels.has(fixture.label)) throw new CollectionError('DUPLICATE_REDIS_LABEL')
    labels.add(fixture.label)
  }
  return options
}

export function parseInfo(raw) {
  const values = {}
  for (const line of String(raw).split(/\r?\n/)) {
    const separator = line.indexOf(':')
    if (separator <= 0 || line.startsWith('#')) continue
    const key = line.slice(0, separator)
    if (!INFO_FIELDS.includes(key)) continue
    const value = line.slice(separator + 1).trim()
    if (!/^[0-9]+$/.test(value)) throw new CollectionError('REDIS_INFO_INVALID')
    const parsed = Number(value)
    if (!Number.isSafeInteger(parsed)) throw new CollectionError('REDIS_INFO_INVALID')
    values[key] = parsed
  }
  if (REQUIRED_FIELDS.some(field => !(field in values))) throw new CollectionError('REDIS_INFO_INCOMPLETE')
  return values
}

function parseClusterInfo(raw) {
  const values = {}
  for (const line of String(raw).split(/\r?\n/)) {
    const separator = line.indexOf(':')
    if (separator <= 0) continue
    const key = line.slice(0, separator)
    if (!CLUSTER_FIELDS.includes(key)) continue
    const value = line.slice(separator + 1).trim()
    values[key] = /^[0-9]+$/.test(value) ? Number(value) : value
  }
  if (values.cluster_state !== 'ok') throw new CollectionError('REDIS_CLUSTER_UNHEALTHY')
  return values
}

async function readFixture(path) {
  try {
    return await fs.readFile(path === '-' ? 0 : path, 'utf8')
  } catch {
    throw new CollectionError('REDIS_FIXTURE_UNAVAILABLE')
  }
}

async function loadIORedis(moduleName) {
  try {
    let specifier = moduleName
    if (!specifier.includes('/') && !specifier.startsWith('file:')) {
      const requireFromWorkingDirectory = createRequire(pathToFileURL(`${process.cwd()}/package.json`))
      specifier = pathToFileURL(requireFromWorkingDirectory.resolve(specifier)).href
    } else if (specifier.startsWith('/')) {
      specifier = pathToFileURL(specifier).href
    }
    const imported = await import(specifier)
    const Redis = imported.Redis || imported.default
    const Cluster = imported.Cluster || Redis?.Cluster
    if (typeof Redis !== 'function' || typeof Cluster !== 'function') throw new Error('invalid module')
    return { Redis, Cluster }
  } catch {
    throw new CollectionError('IOREDIS_UNAVAILABLE')
  }
}

function clusterURL(url) {
  return url.includes(',') || /^rediss?:\/\/clustercfg\./i.test(url)
}

function clusterOptions(url) {
  const secure = url.startsWith('rediss://')
  const protocol = secure ? 'rediss:' : 'redis:'
  const parsed = url.replace(/^rediss?:\/\//, '').split(',').map(value => new URL(`${protocol}//${value}`))
  const nodes = parsed.map(value => ({ host: value.hostname, port: Number(value.port || 6379) }))
  const first = parsed[0]
  const redisOptions = {
    lazyConnect: true, enableOfflineQueue: false, maxRetriesPerRequest: 0,
    connectTimeout: 5000, commandTimeout: 5000
  }
  if (first?.username) redisOptions.username = decodeURIComponent(first.username)
  if (first?.password) redisOptions.password = decodeURIComponent(first.password)
  if (secure) redisOptions.tls = { servername: nodes[0].host }
  return { nodes, redisOptions }
}

function newClient(url, modules) {
  const common = {
    lazyConnect: true, enableOfflineQueue: false, maxRetriesPerRequest: 0,
    connectTimeout: 5000, commandTimeout: 5000
  }
  if (!clusterURL(url)) return { client: new modules.Redis(url, common), cluster: false }
  const options = clusterOptions(url)
  return {
    client: new modules.Cluster(options.nodes, {
      lazyConnect: true, enableOfflineQueue: false, enableReadyCheck: true,
      clusterRetryStrategy: () => null, redisOptions: options.redisOptions
    }),
    cluster: true
  }
}

async function observeSource(source, modules) {
  const url = process.env[source.value]
  if (!url) throw new CollectionError('REDIS_CONNECTION_ENV_MISSING')
  const { client, cluster } = newClient(url, modules)
  client.on('error', () => {})
  try {
    await client.connect()
    const clients = cluster ? client.nodes('master') : [client]
    if (!Array.isArray(clients) || clients.length === 0) throw new CollectionError('REDIS_MASTERS_MISSING')
    const nodes = []
    for (let index = 0; index < clients.length; index += 1) {
      const startedAt = process.hrtime.bigint()
      const pong = await clients[index].ping()
      if (pong !== 'PONG') throw new CollectionError('REDIS_PING_FAILED')
      const pingLatencyMs = Number(process.hrtime.bigint() - startedAt) / 1_000_000
      if (!Number.isFinite(pingLatencyMs) || pingLatencyMs < 0) {
        throw new CollectionError('REDIS_PING_FAILED')
      }
      nodes.push({
        label: cluster ? `master-${index + 1}` : 'primary',
        pingLatencyMs: Math.round(pingLatencyMs * 1000) / 1000,
        info: parseInfo(await clients[index].info('all'))
      })
    }
    const result = { label: source.label, mode: cluster ? 'cluster' : 'standalone', nodes }
    if (cluster) result.cluster = parseClusterInfo(await client.cluster('INFO'))
    return result
  } catch (error) {
    if (error instanceof CollectionError) throw error
    throw new CollectionError('REDIS_QUERY_FAILED')
  } finally {
    client.disconnect()
  }
}

export async function run(argv) {
  let options
  try {
    options = parseArgs(argv)
  } catch (error) {
    const result = baseResult({
      environment: 'test1', phase: 'start', runId: 'invalid',
      candidateManifestSha256: `sha256:${'0'.repeat(64)}`, fixtures: []
    })
    addCheck(result, 'redis-setup', false, error instanceof CollectionError ? error.code : 'INVALID_ARGUMENT')
    return result
  }
  const result = baseResult(options)
  let modules
  const configuredSources = []
  for (const source of options.sources) {
    if (!process.env[source.value]) addCheck(result, `redis-${source.label}`, false, 'REDIS_CONNECTION_ENV_MISSING')
    else configuredSources.push(source)
  }
  if (configuredSources.length > 0) {
    try {
      modules = await loadIORedis(options.module)
    } catch (error) {
      addCheck(result, 'redis-module', false, error.code)
    }
  }
  for (const fixture of options.fixtures) {
    try {
      result.raw.sources.push({
        label: fixture.label,
        mode: 'fixture',
        nodes: [{ label: 'primary', pingLatencyMs: 0, info: parseInfo(await readFixture(fixture.value)) }]
      })
      addCheck(result, `redis-${fixture.label}`, true)
    } catch (error) {
      addCheck(result, `redis-${fixture.label}`, false, error instanceof CollectionError ? error.code : 'REDIS_FIXTURE_UNAVAILABLE')
    }
  }
  if (modules) {
    for (const source of configuredSources) {
      try {
        result.raw.sources.push(await observeSource(source, modules))
        addCheck(result, `redis-${source.label}`, true)
      } catch (error) {
        addCheck(result, `redis-${source.label}`, false, error instanceof CollectionError ? error.code : 'REDIS_QUERY_FAILED')
      }
    }
  }
  result.raw.sources.sort((left, right) => left.label.localeCompare(right.label))
  return result
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMain) {
  const result = await run(process.argv.slice(2))
  process.stdout.write(`${JSON.stringify(result)}\n`)
  process.exitCode = result.status === 'COLLECTED' ? 0 : 2
}
