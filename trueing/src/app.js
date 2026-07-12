require('dotenv').config();

const express = require('express');
const path = require('path');
const helmet = require('helmet');
const cors = require('cors');
const rateLimit = require('express-rate-limit');

const { errorHandler } = require('./middleware/errorHandler');
const { authenticate } = require('./middleware/auth');
const { planGate } = require('./middleware/planGate');
const { UPLOAD_DIR } = require('./middleware/upload');

const authRoutes = require('./routes/auth');
const uploadRoutes = require('./routes/upload');
const jobsRoutes = require('./routes/jobs');
const { statusHandler } = require('./routes/jobs');
const { regenerateHandler, getVariantsHandler } = require('./routes/regenerate');
const webhookRoutes = require('./routes/webhook');
const billingRoutes = require('./routes/billing');

const app = express();

app.use(helmet());
app.use(cors({ origin: process.env.CORS_ORIGIN || '*' }));

app.use(
  rateLimit({
    windowMs: 15 * 60 * 1000,
    max: Number(process.env.RATE_LIMIT_MAX) || 300,
    standardHeaders: true,
    legacyHeaders: false,
  }),
);

app.use('/webhook', express.raw({ type: 'application/json' }), webhookRoutes);

app.use(express.json({ limit: '1mb' }));

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'trueing-api' });
});

app.use('/files', express.static(UPLOAD_DIR, { maxAge: '1d', fallthrough: false }));

app.use('/auth', authRoutes);
app.use('/upload', uploadRoutes);

app.get('/jobs/:id/status', statusHandler);

app.use('/jobs', authenticate, jobsRoutes);
app.post('/regenerate', authenticate, planGate(['pro', 'agency']), regenerateHandler);
app.get('/api/jobs/:id/variants', authenticate, getVariantsHandler);
app.use('/api/billing', authenticate, billingRoutes);

app.use((_req, res) => {
  res.status(404).json({ error: 'Not found' });
});

app.use(errorHandler);

module.exports = app;
