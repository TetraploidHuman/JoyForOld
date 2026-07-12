const Redis = require('ioredis');

let connection;

function getRedisConnection() {
  if (!connection) {
    const url = process.env.REDIS_URL || 'redis://127.0.0.1:6379';
    connection = new Redis(url, { maxRetriesPerRequest: null });
  }
  return connection;
}

module.exports = { getRedisConnection };
