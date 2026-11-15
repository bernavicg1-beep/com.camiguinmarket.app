/**
 * Tests for the scheduled purge: expired perishable listings are deleted from
 * Firestore and their photos are removed from R2.
 *
 * Everything runs against a stubbed Firestore/R2, so no credentials and no
 * emulator are needed. Run with: npm test  (inside `worker/`)
 */

import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import test from "node:test";

import worker from "../index.js";

const PROJECT = "demo-camiguin";
const PHOTO = "https://worker.example/i";

const PRIVATE_KEY = generateKeyPairSync("rsa", { modulusLength: 2048 })
  .privateKey.export({ type: "pkcs8", format: "pem" });

function jsonResponse(body) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

/** One `listings` document as the REST API returns it. */
function firestoreDoc({ id, isPerishable = true, expiresAt, photos = [] }) {
  const fields = {
    isPerishable: { booleanValue: isPerishable },
    expiresAt: { integerValue: String(expiresAt) },
    imageUrl: { stringValue: photos[0] || "" },
    imageUrls: { arrayValue: { values: photos.map((url) => ({ stringValue: url })) } },
  };
  return {
    document: {
      name: `projects/${PROJECT}/databases/(default)/documents/listings/${id}`,
      fields,
    },
  };
}

const now = Date.now();
const expired = (id, photos = []) =>
  firestoreDoc({ id, expiresAt: now - 60_000, photos });

/**
 * Stub the Google endpoints. `pages` is what each `runQuery` call returns;
 * `failDeletes` lists document ids whose DELETE answers 500; `failPhotoKeys`
 * lists R2 keys whose delete throws.
 */
function stub({ pages = [], failDeletes = [], failPhotoKeys = [], serviceAccount = true } = {}) {
  const deletedDocs = [];
  const deletedPhotos = [];
  const calls = { runQuery: 0, queries: [] };

  globalThis.fetch = async (url, init = {}) => {
    const href = String(url);

    if (href.includes("oauth2.googleapis.com/token")) {
      return jsonResponse({ access_token: "stub-access-token", expires_in: 3600 });
    }

    if (href.includes(":runQuery")) {
      const query = JSON.parse(init.body);
      calls.queries.push(query);
      const page = pages[calls.runQuery] || [];
      calls.runQuery += 1;
      return jsonResponse(page);
    }

    if (init.method === "DELETE" && href.includes("/documents/listings/")) {
      const id = href.split("/documents/listings/")[1];
      deletedDocs.push(id);
      return new Response("", { status: failDeletes.includes(id) ? 500 : 200 });
    }

    throw new Error(`unexpected fetch: ${href}`);
  };

  const env = {
    IMAGES: {
      async delete(key) {
        deletedPhotos.push(key);
        if (failPhotoKeys.includes(key)) throw new Error("R2 unavailable");
      },
    },
  };

  if (serviceAccount) {
    env.FIREBASE_SERVICE_ACCOUNT = JSON.stringify({
      project_id: PROJECT,
      client_email: "worker@demo-camiguin.iam.gserviceaccount.com",
      private_key: PRIVATE_KEY,
    });
  }

  return { env, deletedDocs, deletedPhotos, calls };
}

/** Run the cron handler and wait for the work it scheduled. */
function runScheduled(env) {
  return new Promise((resolve, reject) => {
    worker.scheduled(
      { cron: "*/15 * * * *", scheduledTime: Date.now() },
      env,
      { waitUntil: (promise) => promise.then(resolve, reject), passThroughOnException() {} },
    );
  });
}

test("without the service account nothing is touched", async () => {
  const { env, deletedDocs, deletedPhotos, calls } = stub({ serviceAccount: false });

  await runScheduled(env);

  assert.deepEqual(deletedDocs, []);
  assert.deepEqual(deletedPhotos, []);
  assert.equal(calls.runQuery, 0);
});

test("deletes expired perishable listings and their photos", async () => {
  const { env, deletedDocs, deletedPhotos, calls } = stub({
    pages: [
      [
        expired("fish", [`${PHOTO}/listing_images/u1/fish.jpg`, `${PHOTO}/listing_images/u1/fish-2.jpg`]),
        expired("fruit", [`${PHOTO}/listing_images/u2/fruit%20bowl.jpg`]),
        // Still fresh: must stay.
        firestoreDoc({ id: "fresh", expiresAt: now + 3_600_000, photos: [`${PHOTO}/listing_images/u3/fresh.jpg`] }),
        // No expiration at all (non-perishable): must stay.
        firestoreDoc({ id: "durable", isPerishable: false, expiresAt: 0, photos: [`${PHOTO}/listing_images/u4/durable.jpg`] }),
      ],
    ],
  });

  await runScheduled(env);

  assert.deepEqual(deletedDocs, ["fish", "fruit"]);
  assert.deepEqual(deletedPhotos, [
    "listing_images/u1/fish.jpg",
    "listing_images/u1/fish-2.jpg",
    "listing_images/u2/fruit bowl.jpg",
  ]);
  assert.equal(calls.runQuery, 1);
});

test("the query only selects a past expiration range", async () => {
  const { env, calls } = stub({ pages: [[]] });

  await runScheduled(env);

  const filters = calls.queries[0].structuredQuery.where.compositeFilter.filters;
  assert.deepEqual(
    filters.map((f) => [f.fieldFilter.field.fieldPath, f.fieldFilter.op]),
    [
      ["expiresAt", "GREATER_THAN"],
      ["expiresAt", "LESS_THAN_OR_EQUAL"],
    ],
  );
  assert.equal(filters[0].fieldFilter.value.integerValue, "0");
  assert.ok(Number(filters[1].fieldFilter.value.integerValue) >= now);
});

test("photo URLs are deduplicated and only listing_images keys are removed", async () => {
  const { env, deletedPhotos } = stub({
    pages: [
      [
        firestoreDoc({
          id: "dup",
          expiresAt: now - 1_000,
          photos: [
            `${PHOTO}/listing_images/u1/same.jpg`,
            `${PHOTO}/listing_images/u1/same.jpg`,
            "https://elsewhere.example/not-r2.jpg",
            "https://worker.example/i/other_folder/keep.jpg",
          ],
        }),
      ],
    ],
  });

  await runScheduled(env);

  assert.deepEqual(deletedPhotos, ["listing_images/u1/same.jpg"]);
});

test("a failed document delete keeps that listing's photos for the next run", async () => {
  const { env, deletedDocs, deletedPhotos } = stub({
    pages: [
      [
        expired("stuck", [`${PHOTO}/listing_images/u1/stuck.jpg`]),
        expired("gone", [`${PHOTO}/listing_images/u2/gone.jpg`]),
      ],
    ],
    failDeletes: ["stuck"],
  });

  await runScheduled(env);

  // Both were attempted, only the successful one lost its photo.
  assert.deepEqual(deletedDocs.sort(), ["gone", "stuck"]);
  assert.deepEqual(deletedPhotos, ["listing_images/u2/gone.jpg"]);
});

test("a failing R2 delete is counted but does not stop the run", async () => {
  const { env, deletedDocs, deletedPhotos } = stub({
    pages: [
      [expired("a", [`${PHOTO}/listing_images/u1/a.jpg`, `${PHOTO}/listing_images/u1/b.jpg`])],
    ],
    failPhotoKeys: ["listing_images/u1/a.jpg"],
  });

  await runScheduled(env);

  assert.deepEqual(deletedDocs, ["a"]);
  assert.equal(deletedPhotos.length, 2);
});

test("nothing deletable means one query and no deletes", async () => {
  const { env, deletedDocs, calls } = stub({
    pages: [[firestoreDoc({ id: "fresh", expiresAt: now + 10_000 })]],
  });

  await runScheduled(env);

  assert.equal(calls.runQuery, 1);
  assert.deepEqual(deletedDocs, []);
});

test("a run that cannot delete anything stops after one batch", async () => {
  const { env, deletedDocs, calls } = stub({
    pages: [[expired("x"), expired("y")]],
    failDeletes: ["x", "y"],
  });

  await runScheduled(env);

  assert.equal(calls.runQuery, 1);
  assert.deepEqual(deletedDocs.sort(), ["x", "y"]);
});

test("a full batch is followed by another query until the backlog is empty", async () => {
  // The Worker fetches 200 documents at a time.
  const bigPage = Array.from({ length: 200 }, (_, i) => expired(`bulk${i}`));
  const { env, deletedDocs, calls } = stub({
    pages: [bigPage, [expired("tail1"), expired("tail2")], []],
  });

  await runScheduled(env);

  assert.equal(calls.runQuery, 2);
  assert.equal(deletedDocs.length, 202);
});

test("a spare page after a short batch is not fetched", async () => {
  const { env, calls } = stub({ pages: [[expired("only")], []] });

  await runScheduled(env);

  assert.equal(calls.runQuery, 1);
});
