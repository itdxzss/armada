import kafkaJs from 'kafkajs'

const { Kafka, logLevel } = kafkaJs

class ContractError extends Error {}

function csv(value) {
  return String(value ?? '')
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
}

function expectedTopics(value) {
  return csv(value).map(item => {
    const separator = item.lastIndexOf('=')
    if (separator <= 0) {
      throw new Error('invalid expected topic entry')
    }
    const name = item.slice(0, separator)
    const partitions = Number(item.slice(separator + 1))
    if (!Number.isInteger(partitions) || partitions <= 0) {
      throw new Error('invalid expected topic partition count')
    }
    return { name, partitions }
  })
}

const topics = expectedTopics(process.env.EXPECTED_KAFKA_TOPICS)
const groups = csv(process.env.EXPECTED_KAFKA_GROUPS)

if (topics.length === 0 && groups.length === 0) {
  console.log('Kafka exact metadata: SKIPPED')
  process.exit(0)
}

const brokers = csv(process.env.KAFKA_BROKERS)
if (brokers.length === 0) {
  throw new Error('Kafka brokers are not configured')
}

const config = {
  clientId: 'armada-deploy-read-only-check',
  brokers,
  logLevel: logLevel.NOTHING
}

if (/^(1|true|yes)$/i.test(process.env.KAFKA_SSL ?? '')) {
  config.ssl = true
}

if (process.env.KAFKA_USERNAME || process.env.KAFKA_PASSWORD) {
  config.sasl = {
    mechanism: process.env.KAFKA_SASL_MECHANISM || 'plain',
    username: process.env.KAFKA_USERNAME || '',
    password: process.env.KAFKA_PASSWORD || ''
  }
}

const kafka = new Kafka(config)
const admin = kafka.admin()

try {
  await admin.connect()
  if (topics.length > 0) {
    const metadata = await admin.fetchTopicMetadata({
      topics: topics.map(topic => topic.name)
    })
    const actualTopics = new Map(
      metadata.topics.map(topic => [topic.name, topic.partitions.length])
    )
    for (const topic of topics) {
      const actualPartitions = actualTopics.get(topic.name)
      if (actualPartitions !== topic.partitions) {
        throw new ContractError(
          'topic contract failed: ' + topic.name +
          ' expected=' + topic.partitions +
          ' actual=' + (actualPartitions ?? 'missing')
        )
      }
      console.log('topic OK: ' + topic.name + ' partitions=' + actualPartitions)
    }
  }

  if (groups.length > 0) {
    const listed = await admin.listGroups()
    const actualGroups = new Set(listed.groups.map(group => group.groupId))
    const described = await admin.describeGroups(groups)
    const groupStates = new Map(
      described.groups.map(group => [group.groupId, group.state])
    )
    for (const group of groups) {
      if (!actualGroups.has(group)) {
        throw new ContractError('consumer group missing: ' + group)
      }
      const state = groupStates.get(group)
      if (!state) {
        throw new ContractError('consumer group state missing: ' + group)
      }
      console.log('consumer group OK: ' + group + ' state=' + state)
    }
  }
} catch (error) {
  if (error instanceof ContractError) {
    console.error(error.message)
  } else {
    console.error('Kafka metadata connection/check failed (details redacted)')
  }
  process.exitCode = 1
} finally {
  await admin.disconnect().catch(() => {})
}
