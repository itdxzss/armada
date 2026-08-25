const failure = new URL(import.meta.url).searchParams.get('failure')

function fail(step) {
  if (failure !== step) return
  const error = new Error('must-not-appear broker://secret.example:9092')
  error.name = 'KafkaJSProtocolError'
  error.type = 'UNKNOWN_TOPIC_OR_PARTITION'
  error.broker = 'secret.example:9092'
  throw error
}

export const logLevel = { NOTHING: 0 }

export class Kafka {
  constructor() {
    fail('constructor')
  }

  admin() {
    return {
      async connect() {
        if (failure === 'connect-timeout') return new Promise(() => {})
        fail('connect')
      },
      async fetchTopicOffsets() {
        if (failure === 'negative-timeout-warning') setTimeout(() => {}, -Date.now())
        fail('topic')
        return [{ partition: 0, low: '0', high: '10' }]
      },
      async fetchOffsets({ topics }) {
        fail('group')
        return [{ topic: topics[0], partitions: [{ partition: 0, offset: '10' }] }]
      },
      async disconnect() {
        fail('disconnect')
      }
    }
  }
}
