const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_velo_123";
const BOB_UID = "bob_velo_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- 1. UNAUTHENTICATED DENIAL ---

test("Unauthenticated users cannot read or write any user subcollection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection(`users/${ALICE_UID}/vaultItems`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/shortcuts`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/readingList`).get());
  await assertFails(unauthDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).get());
  await assertFails(unauthDb.doc(`users/${ALICE_UID}/preferences/browser`).get());

  await assertFails(
    unauthDb.doc(`users/${ALICE_UID}/vaultItems/item_1`).set({
      userId: ALICE_UID,
      encryptedPayload: "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=",
      iv: "MTIzNDU2Nzg5MDEy",
      cryptoVersion: 1,
      createdAt: now,
      updatedAt: now,
    })
  );
});

// --- 2. VAULT CONFIG (E2EE METADATA) ---

test("VaultConfig: owner create/update succeeds; invalid KDF, ghost fields, and cross-user fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  const validConfig = {
    userId: ALICE_UID,
    saltBase64: "c2FsdDEyMzQ1Njc4OTAxMjM0NTY=",
    verifierCiphertext: "ZW5jcnlwdGVkX3ZlcmlmaWVyX3NlbnRpbmVsX2J5dGVz",
    verifierIv: "MTIzNDU2Nzg5MDEy",
    kdfIterations: 120000,
    keyFingerprint: "9F86D081884C7D65",
    createdAt: created,
    updatedAt: created,
  };

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).set(validConfig)
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).get()
  );

  // Valid passphrase rotation update
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).update({
      saltBase64: "bmV3c2FsdDEyMzQ1Njc4OTAxMjM=",
      verifierCiphertext: "bmV3X2VuY3J5cHRlZF92ZXJpZmllcl9zZW50aW5lbA==",
      verifierIv: "OTg3NjU0MzIxMDk4",
      kdfIterations: 150000,
      keyFingerprint: "A1B2C3D4E5F60718",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject weak KDF iterations (< 100000)
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).update({
      kdfIterations: 1000,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject Bob reading or writing Alice's vaultConfig
  await assertFails(bobDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/vaultConfig/primary`).delete());
});

// --- 3. VAULT ITEMS (E2EE PASSWORD MANAGER) ---

test("VaultItems: owner CRUD succeeds; immutable fields, @DocumentId key, future timestamps, cross-user fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/vaultItems/cred_1`).set({
      userId: ALICE_UID,
      encryptedPayload: "ZW5jcnlwdGVkX2NyZWRlbnRpYWxfcGF5bG9hZA==",
      iv: "MTIzNDU2Nzg5MDEy",
      cryptoVersion: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Owner list query succeeds
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/vaultItems`).get());

  // Partial update succeeds
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/vaultItems/cred_1`).update({
      encryptedPayload: "dXBkYXRlZF9lbmNyeXB0ZWRfcGF5bG9hZA==",
      iv: "YWJjZGVmZ2hpamts",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject @DocumentId `id` field in create payload
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/vaultItems/cred_2`).set({
      id: "cred_2",
      userId: ALICE_UID,
      encryptedPayload: "ZW5jcnlwdGVkX2NyZWRlbnRpYWxfcGF5bG9hZA==",
      iv: "MTIzNDU2Nzg5MDEy",
      cryptoVersion: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/vaultItems/cred_3`).set({
      userId: ALICE_UID,
      encryptedPayload: "ZW5jcnlwdGVkX2NyZWRlbnRpYWxfcGF5bG9hZA==",
      iv: "MTIzNDU2Nzg5MDEy",
      cryptoVersion: 1,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Reject immutable createdAt tamper on update
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/vaultItems/cred_1`).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject oversized payload (> 8192)
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/vaultItems/cred_big`).set({
      userId: ALICE_UID,
      encryptedPayload: "A".repeat(9000),
      iv: "MTIzNDU2Nzg5MDEy",
      cryptoVersion: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject Bob reading/listing/updating/deleting Alice's vault items
  await assertFails(bobDb.collection(`users/${ALICE_UID}/vaultItems`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/vaultItems/cred_1`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/vaultItems/cred_1`).update({
      encryptedPayload: "aGFja2VkX3BheWxvYWRfZGF0YQ==",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(bobDb.doc(`users/${ALICE_UID}/vaultItems/cred_1`).delete());

  // Owner delete succeeds
  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/vaultItems/cred_1`).delete());
});

// --- 4. SHORTCUTS (E2EE HOMEPAGE SHORTCUTS) ---

test("Shortcuts: owner create, order update, list, delete succeed; invalid bounds and cross-user fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 8000);

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/shortcuts/sc_1`).set({
      userId: ALICE_UID,
      encryptedPayload: "ZW5jcnlwdGVkX3Nob3J0Y3V0X3BheWxvYWQ=",
      iv: "MTIzNDU2Nzg5MDEy",
      orderIndex: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/shortcuts`).orderBy("orderIndex").get()
  );

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/shortcuts/sc_1`).update({
      orderIndex: 3,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject out-of-range orderIndex
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/shortcuts/sc_1`).update({
      orderIndex: -1,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject Bob
  await assertFails(bobDb.collection(`users/${ALICE_UID}/shortcuts`).get());

  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/shortcuts/sc_1`).delete());
});

// --- 5. READING LIST (E2EE OFFLINE READING SYNC) ---

test("ReadingList: owner create, archive toggle, payload update, delete succeed; spoofing fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 6000);

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/readingList/page_1`).set({
      userId: ALICE_UID,
      encryptedPayload: "ZW5jcnlwdGVkX3JlYWRpbmdfbWV0YWRhdGE=",
      iv: "MTIzNDU2Nzg5MDEy",
      urlHash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      archived: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/readingList`).get()
  );

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/readingList/page_1`).update({
      archived: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject userId spoofing on create
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/readingList/page_spoof`).set({
      userId: BOB_UID,
      encryptedPayload: "ZW5jcnlwdGVkX3JlYWRpbmdfbWV0YWRhdGE=",
      iv: "MTIzNDU2Nzg5MDEy",
      urlHash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      archived: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertFails(bobDb.doc(`users/${ALICE_UID}/readingList/page_1`).get());
  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/readingList/page_1`).delete());
});

// --- 6. BROWSER PREFERENCES (CROSS-PLATFORM SYNC) ---

test("Preferences: owner create & partial preference updates succeed; invalid enum fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 7000);

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/preferences/browser`).set({
      userId: ALICE_UID,
      themeMode: "DARK",
      adBlockEnabled: true,
      httpsOnlyEnabled: true,
      darkWebPagesEnabled: true,
      notificationsEnabled: true,
      searchEngine: "DUCKDUCKGO",
      trackersBlockedTotal: 42,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Action-based partial updates
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/preferences/browser`).update({
      themeMode: "LIGHT",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/preferences/browser`).update({
      trackersBlockedTotal: 55,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject invalid enum
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/preferences/browser`).update({
      themeMode: "NEON_PURPLE",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/preferences/browser`).update({
      searchEngine: "BING",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject Bob
  await assertFails(bobDb.doc(`users/${ALICE_UID}/preferences/browser`).get());
});
