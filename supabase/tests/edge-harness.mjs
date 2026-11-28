// Shared setup for the cleanup-provider tests: fake providers, fixtures and the
// tiny runner. No credentials, no network, no Supabase project.
export {
  authorized,
  cloudinaryRequest,
  deleteObject,
  providerReady,
  r2Request,
  resourceType,
  runCleanup,
} from "../functions/cleanup-provider/lib.js";

export const ENV = {
  CLOUDINARY_CLOUD_NAME: "camiguin",
  CLOUDINARY_API_KEY: "123456789",
  CLOUDINARY_API_SECRET: "cloudinary-secret",
  R2_ACCOUNT_ID: "acc-1",
  R2_BUCKET: "camiguin-images",
  R2_API_TOKEN: "cf-token",
  CLEANUP_SECRET: "cron-secret",
  SUPABASE_SERVICE_ROLE_KEY: "service-role-key",
};

let seq = 0;
export const row = (over = {}) => {
  seq += 1;
  return {
    id: `00000000-0000-4000-8000-0000000000${String(seq).padStart(2, "0")}`,
    seller_id: "00000000-0000-4000-8000-00000000000b",
    provider: "r2",
    storage_key: "listing_images/u1/1700000000-abcd.jpg",
    public_url: "https://worker.example/i/listing_images/u1/1700000000-abcd.jpg",
    mime_type: "image/jpeg",
    bytes: 4000,
    queued_at: "2026-11-28T02:15:00.000Z",
    ...over,
  };
};

/** A fetch stand-in: records calls, replays canned responses. */
export function fakeFetch(responses) {
  const calls = [];
  const queue = [...responses];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    const next = queue.length > 1 ? queue.shift() : queue[0] || {};
    return new Response(next.body ?? "", { status: next.status ?? 200 });
  };
  return { fetchImpl, calls };
}

export const ok = (body) => ({ status: 200, body: JSON.stringify(body) });

let passes = 0;
let failures = 0;

export async function test(name, fn) {
  try {
    await fn();
    passes += 1;
    console.log(`ok   ${name}`);
  } catch (error) {
    failures += 1;
    console.log(`FAIL ${name}\n     ${error.message.split("\n")[0]}`);
  }
}

export function report() {
  console.log(`\n${passes} passed, ${failures} failed`);
  if (failures) process.exit(1);
}
