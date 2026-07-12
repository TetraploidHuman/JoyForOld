const path = require('path');
const fs = require('fs');
const multer = require('multer');
const { v4: uuidv4 } = require('uuid');
const { httpError } = require('./errorHandler');

const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(process.cwd(), 'uploads');
const MAX_BYTES = Number(process.env.UPLOAD_MAX_BYTES) || 15 * 1024 * 1024;

if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, UPLOAD_DIR),
  filename: (_req, file, cb) => {
    const ext = path.extname(file.originalname) || '.jpg';
    cb(null, `${uuidv4()}${ext}`);
  },
});

function fileFilter(_req, file, cb) {
  const ok = /^image\/(jpeg|png|webp|gif)$/i.test(file.mimetype);
  if (!ok) return cb(httpError(400, 'Only image uploads are allowed', 'INVALID_FILE'));
  cb(null, true);
}

const upload = multer({
  storage,
  limits: { fileSize: MAX_BYTES },
  fileFilter,
});

module.exports = { upload, UPLOAD_DIR };
