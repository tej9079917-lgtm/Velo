'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const KDF_ITERATIONS = 210000;
const KEY_LENGTH = 32;
const GCM_IV_BYTES = 12;
const GCM_TAG_BYTES = 16;
const CBC_IV_BYTES = 16;
const RECORD_SALT_BYTES = 32;
const MLV2_PREFIX = 'MLV2:';
const VERIFIER_TEXT = 'VELO_E2EE_VERIFIER_V2';
const VAULT_MAGIC = Buffer.from('VLT1', 'ascii');
const VAULT_CHUNK_SIZE = 512 * 1024; // 512 KB

function hkdfSha256(ikm, salt, info, length = 32) {
  const prk = crypto.createHmac('sha256', salt).update(ikm).digest();
  const infoBuf = Buffer.from(info, 'utf8');
  let t = Buffer.alloc(0);
  const chunks = [];
  let counter = 1;
  let totalLen = 0;
  while (totalLen < length) {
    const hmac = crypto.createHmac('sha256', prk);
    hmac.update(t);
    hmac.update(infoBuf);
    hmac.update(Buffer.from([counter++]));
    t = hmac.digest();
    chunks.push(t);
    totalLen += t.length;
  }
  return Buffer.concat(chunks, length).subarray(0, length);
}

function deriveMasterKey(passphrase, saltBuf, iterations = KDF_ITERATIONS) {
  return crypto.pbkdf2Sync(
    Buffer.from(passphrase, 'utf8'),
    saltBuf,
    iterations,
    KEY_LENGTH,
    'sha256'
  );
}

function keyFingerprint(masterKey) {
  const hash = crypto.createHash('sha256').update(masterKey).digest('hex').toUpperCase();
  return `${hash.slice(0, 4)}-${hash.slice(4, 8)}-${hash.slice(8, 12)}`;
}

function createVaultConfig(passphrase) {
  const salt = crypto.randomBytes(32);
  const masterKey = deriveMasterKey(passphrase, salt, KDF_ITERATIONS);
  const verifier = encryptMultiLayer(VERIFIER_TEXT, masterKey);
  return {
    config: {
      saltBase64: salt.toString('base64'),
      iterations: KDF_ITERATIONS,
      verifierCiphertext: verifier.ciphertext,
      verifierIv: verifier.iv,
      keyFingerprint: keyFingerprint(masterKey),
      createdAt: Date.now()
    },
    masterKey
  };
}

function verifyAndUnlockVault(passphrase, config) {
  if (!config || !config.saltBase64 || !config.verifierCiphertext) return null;
  try {
    const salt = Buffer.from(config.saltBase64, 'base64');
    const iterations = Number(config.iterations) || KDF_ITERATIONS;
    const masterKey = deriveMasterKey(passphrase, salt, iterations);
    const decrypted = decryptMultiLayer(config.verifierCiphertext, config.verifierIv || '', masterKey);
    const expected = Buffer.from(VERIFIER_TEXT, 'utf8');
    const actual = Buffer.from(decrypted, 'utf8');
    if (actual.length === expected.length && crypto.timingSafeEqual(actual, expected)) {
      return masterKey;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * 3-Layer Defense-in-Depth Multi-Layer Encryption (MLV2:):
 *  - Layer 1: Random 32B record salt + Ephemeral 256-bit DEK + random 16..63B length padding + AES-256-GCM
 *  - Layer 2: HKDF-SHA256 sub-keys + AES-256-CBC wrapping (DEK + Layer-1) + Encrypt-then-MAC HMAC-SHA256
 *  - Layer 3: Independent HKDF-SHA256 outer key + AES-256-GCM envelope
 */
function encryptMultiLayer(plaintext, masterKey) {
  const recordSalt = crypto.randomBytes(RECORD_SALT_BYTES);
  const dek = crypto.randomBytes(32);

  // Layer 1: Length obfuscation padding + Ephemeral DEK AES-256-GCM
  const plainBuf = Buffer.from(String(plaintext), 'utf8');
  const padLen = 16 + crypto.randomInt(0, 48); // 16..63 bytes
  const padding = crypto.randomBytes(padLen);
  const l1Plain = Buffer.alloc(4 + plainBuf.length + 1 + padLen);
  l1Plain.writeUInt32BE(plainBuf.length, 0);
  plainBuf.copy(l1Plain, 4);
  l1Plain[4 + plainBuf.length] = padLen;
  padding.copy(l1Plain, 5 + plainBuf.length);

  const l1Iv = crypto.randomBytes(GCM_IV_BYTES);
  const l1Cipher = crypto.createCipheriv('aes-256-gcm', dek, l1Iv);
  l1Cipher.setAAD(Buffer.from('VELO_L1_CRED_V2', 'utf8'));
  const l1Enc = Buffer.concat([l1Cipher.update(l1Plain), l1Cipher.final()]);
  const l1Tag = l1Cipher.getAuthTag();

  // Layer 2: AES-256-CBC + HMAC-SHA256 (Encrypt-then-MAC)
  const wrapEncKey = hkdfSha256(masterKey, recordSalt, 'VELO_WRAP_ENC_V2', 32);
  const wrapMacKey = hkdfSha256(masterKey, recordSalt, 'VELO_WRAP_MAC_V2', 32);
  const l2Plain = Buffer.concat([dek, l1Iv, l1Tag, l1Enc]);
  const l2Iv = crypto.randomBytes(CBC_IV_BYTES);
  const l2Cipher = crypto.createCipheriv('aes-256-cbc', wrapEncKey, l2Iv);
  const l2Enc = Buffer.concat([l2Cipher.update(l2Plain), l2Cipher.final()]);
  const l2Mac = crypto.createHmac('sha256', wrapMacKey).update(l2Iv).update(l2Enc).digest();

  // Layer 3: Outer AES-256-GCM seal
  const outerKey = hkdfSha256(masterKey, recordSalt, 'VELO_OUTER_SEAL_V2', 32);
  const outerIv = crypto.randomBytes(GCM_IV_BYTES);
  const outerPayload = Buffer.concat([l2Iv, l2Mac, l2Enc]);
  const l3Cipher = crypto.createCipheriv('aes-256-gcm', outerKey, outerIv);
  l3Cipher.setAAD(Buffer.from('VELO_L3_SEAL_V2', 'utf8'));
  const l3Enc = Buffer.concat([l3Cipher.update(outerPayload), l3Cipher.final()]);
  const l3Tag = l3Cipher.getAuthTag();

  // Wipe ephemeral buffers
  dek.fill(0);
  l1Plain.fill(0);
  l2Plain.fill(0);
  wrapEncKey.fill(0);
  wrapMacKey.fill(0);
  outerKey.fill(0);

  const envelope = Buffer.concat([recordSalt, outerIv, l3Tag, l3Enc]);
  return {
    ciphertext: MLV2_PREFIX + envelope.toString('base64'),
    iv: outerIv.toString('base64')
  };
}

function decryptMultiLayer(ciphertextWithPrefix, _ivBase64, masterKey) {
  if (!ciphertextWithPrefix || !ciphertextWithPrefix.startsWith(MLV2_PREFIX)) {
    throw new Error('Unsupported ciphertext format.');
  }
  const envelope = Buffer.from(ciphertextWithPrefix.slice(MLV2_PREFIX.length), 'base64');
  if (envelope.length < RECORD_SALT_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES + 32) {
    throw new Error('Corrupted encrypted envelope.');
  }

  let offset = 0;
  const recordSalt = envelope.subarray(offset, offset + RECORD_SALT_BYTES);
  offset += RECORD_SALT_BYTES;
  const outerIv = envelope.subarray(offset, offset + GCM_IV_BYTES);
  offset += GCM_IV_BYTES;
  const l3Tag = envelope.subarray(offset, offset + GCM_TAG_BYTES);
  offset += GCM_TAG_BYTES;
  const l3Enc = envelope.subarray(offset);

  // Unwrap Layer 3
  const outerKey = hkdfSha256(masterKey, recordSalt, 'VELO_OUTER_SEAL_V2', 32);
  const l3Decipher = crypto.createDecipheriv('aes-256-gcm', outerKey, outerIv);
  l3Decipher.setAAD(Buffer.from('VELO_L3_SEAL_V2', 'utf8'));
  l3Decipher.setAuthTag(l3Tag);
  const outerPayload = Buffer.concat([l3Decipher.update(l3Enc), l3Decipher.final()]);
  outerKey.fill(0);

  // Unwrap Layer 2 (verify HMAC-SHA256 in constant time BEFORE CBC decrypt)
  const l2Iv = outerPayload.subarray(0, CBC_IV_BYTES);
  const l2Mac = outerPayload.subarray(CBC_IV_BYTES, CBC_IV_BYTES + 32);
  const l2Enc = outerPayload.subarray(CBC_IV_BYTES + 32);
  const wrapEncKey = hkdfSha256(masterKey, recordSalt, 'VELO_WRAP_ENC_V2', 32);
  const wrapMacKey = hkdfSha256(masterKey, recordSalt, 'VELO_WRAP_MAC_V2', 32);
  const expectedMac = crypto.createHmac('sha256', wrapMacKey).update(l2Iv).update(l2Enc).digest();
  if (!crypto.timingSafeEqual(l2Mac, expectedMac)) {
    wrapEncKey.fill(0);
    wrapMacKey.fill(0);
    throw new Error('MAC integrity verification failed.');
  }

  const l2Decipher = crypto.createDecipheriv('aes-256-cbc', wrapEncKey, l2Iv);
  const l2Plain = Buffer.concat([l2Decipher.update(l2Enc), l2Decipher.final()]);
  wrapEncKey.fill(0);
  wrapMacKey.fill(0);

  // Unwrap Layer 1
  const dek = l2Plain.subarray(0, 32);
  const l1Iv = l2Plain.subarray(32, 32 + GCM_IV_BYTES);
  const l1Tag = l2Plain.subarray(32 + GCM_IV_BYTES, 32 + GCM_IV_BYTES + GCM_TAG_BYTES);
  const l1Enc = l2Plain.subarray(32 + GCM_IV_BYTES + GCM_TAG_BYTES);

  const l1Decipher = crypto.createDecipheriv('aes-256-gcm', dek, l1Iv);
  l1Decipher.setAAD(Buffer.from('VELO_L1_CRED_V2', 'utf8'));
  l1Decipher.setAuthTag(l1Tag);
  const l1Plain = Buffer.concat([l1Decipher.update(l1Enc), l1Decipher.final()]);

  const plainLen = l1Plain.readUInt32BE(0);
  if (plainLen > l1Plain.length - 5) {
    throw new Error('Invalid decrypted payload length.');
  }
  const result = l1Plain.subarray(4, 4 + plainLen).toString('utf8');
  l2Plain.fill(0);
  l1Plain.fill(0);
  return result;
}

// --- CHUNKED AES-256-GCM SECRET MEDIA VAULT FILE ENCRYPTION ---

function deriveChunkIv(baseIv, chunkIndex) {
  const iv = Buffer.from(baseIv);
  iv[8] ^= (chunkIndex >>> 24) & 0xff;
  iv[9] ^= (chunkIndex >>> 16) & 0xff;
  iv[10] ^= (chunkIndex >>> 8) & 0xff;
  iv[11] ^= chunkIndex & 0xff;
  return iv;
}

function encryptFileToVault(sourcePath, vaultDir, masterKey) {
  fs.mkdirSync(vaultDir, { recursive: true });
  const encryptedFileName = `${crypto.randomUUID()}.vlt`;
  const destPath = path.join(vaultDir, encryptedFileName);
  const baseIv = crypto.randomBytes(GCM_IV_BYTES);

  const inFd = fs.openSync(sourcePath, 'r');
  const outFd = fs.openSync(destPath, 'w');
  let totalPlain = 0;
  let chunkIdx = 0;
  try {
    fs.writeSync(outFd, VAULT_MAGIC);
    const buf = Buffer.allocUnsafe(VAULT_CHUNK_SIZE);
    while (true) {
      const bytesRead = fs.readSync(inFd, buf, 0, VAULT_CHUNK_SIZE, null);
      if (bytesRead <= 0) break;
      totalPlain += bytesRead;
      const chunkIv = deriveChunkIv(baseIv, chunkIdx++);
      const cipher = crypto.createCipheriv('aes-256-gcm', masterKey, chunkIv);
      const enc = Buffer.concat([cipher.update(buf.subarray(0, bytesRead)), cipher.final()]);
      const tag = cipher.getAuthTag();
      const payload = Buffer.concat([enc, tag]);
      const lenBuf = Buffer.alloc(4);
      lenBuf.writeUInt32BE(payload.length, 0);
      fs.writeSync(outFd, lenBuf);
      fs.writeSync(outFd, payload);
    }
    fs.fsyncSync(outFd);
  } catch (err) {
    try { fs.closeSync(inFd); } catch {}
    try { fs.closeSync(outFd); } catch {}
    try { fs.unlinkSync(destPath); } catch {}
    throw err;
  }
  fs.closeSync(inFd);
  fs.closeSync(outFd);

  if (totalPlain === 0) {
    try { fs.unlinkSync(destPath); } catch {}
    throw new Error('Source file is empty.');
  }

  return {
    encryptedFileName,
    ivBase64: baseIv.toString('base64'),
    byteSize: totalPlain
  };
}

function decryptVaultFileToPath(vaultDir, encryptedFileName, ivBase64, destPath, masterKey) {
  const safeName = path.basename(encryptedFileName);
  const srcPath = path.join(vaultDir, safeName);
  if (!fs.existsSync(srcPath)) {
    throw new Error('Encrypted vault file not found.');
  }
  const baseIv = Buffer.from(ivBase64, 'base64');
  const fileData = fs.readFileSync(srcPath);
  if (fileData.length < 4 || !fileData.subarray(0, 4).equals(VAULT_MAGIC)) {
    throw new Error('Invalid vault file header.');
  }

  const outFd = fs.openSync(destPath, 'w');
  let offset = 4;
  let chunkIdx = 0;
  try {
    while (offset + 4 <= fileData.length) {
      const encLen = fileData.readUInt32BE(offset);
      offset += 4;
      if (encLen < GCM_TAG_BYTES || offset + encLen > fileData.length) {
        throw new Error('Corrupted encrypted chunk.');
      }
      const chunkPayload = fileData.subarray(offset, offset + encLen);
      offset += encLen;
      const encBytes = chunkPayload.subarray(0, encLen - GCM_TAG_BYTES);
      const tag = chunkPayload.subarray(encLen - GCM_TAG_BYTES);
      const chunkIv = deriveChunkIv(baseIv, chunkIdx++);
      const decipher = crypto.createDecipheriv('aes-256-gcm', masterKey, chunkIv);
      decipher.setAuthTag(tag);
      const plain = Buffer.concat([decipher.update(encBytes), decipher.final()]);
      fs.writeSync(outFd, plain);
    }
    fs.fsyncSync(outFd);
  } catch (err) {
    try { fs.closeSync(outFd); } catch {}
    try { fs.unlinkSync(destPath); } catch {}
    throw err;
  }
  fs.closeSync(outFd);
  return destPath;
}

module.exports = {
  KDF_ITERATIONS,
  createVaultConfig,
  verifyAndUnlockVault,
  encryptMultiLayer,
  decryptMultiLayer,
  encryptFileToVault,
  decryptVaultFileToPath
};
