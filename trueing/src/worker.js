require('dotenv').config();

const { createWorker } = require('./jobs/queue');
const { processRenderJob } = require('./jobs/processor');

const worker = createWorker(async (job) => {
  if (job.name !== 'render') return;
  return processRenderJob(job.data);
});

worker.on('completed', (job) => {
  console.log(`[worker] completed ${job.id}`);
});

worker.on('failed', (job, err) => {
  console.error(`[worker] failed ${job?.id}`, err.message);
});

console.log('Trueing render worker started');
