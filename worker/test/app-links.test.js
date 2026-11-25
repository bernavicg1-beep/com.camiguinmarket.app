import assert from "node:assert/strict";
import test from "node:test";

import worker from "../index.js";

const HOST = "https://camiguin-market-images.bernavicg1.workers.dev";

function call(path) {
  const req = new Request(HOST + path);
  return worker.fetch(req, { IMAGES: {} }, { waitUntil() {} });
}

test("assetlinks.json returns both release and debug fingerprints", async () => {
  const res = await call("/.well-known/assetlinks.json");
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type"), /application\/json/);
  const body = await res.json();
  assert.equal(body[0].target.package_name, "com.camiguinmarket.app");
  const fps = body[0].target.sha256_cert_fingerprints;
  assert.ok(fps.includes("C7:3A:F1:15:85:CF:67:95:18:7B:77:6B:46:F8:F3:12:7F:AD:EC:E5:C7:A0:92:05:19:A5:13:97:E7:F6:2A:95"));
  assert.ok(fps.includes("FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C"));
});

test("deep-link fallback serves listing/order/chat", async () => {
  for (const kind of ["listing", "order", "chat"]) {
    const res = await call(`/${kind}/abc123`);
    assert.equal(res.status, 200, kind);
    assert.match(res.headers.get("content-type"), /text\/html/);
    const body = await res.text();
    assert.match(body, new RegExp(`camiguinmarket://${kind}/abc123`));
  }
});

test("deep-link fallback decodes percent-encoded ids", async () => {
  const res = await call("/listing/hello%20world");
  assert.equal(res.status, 200);
  const body = await res.text();
  assert.match(body, /camiguinmarket:\/\/listing\/hello%20world/);
});

test("deep-link fallback neutralizes script payloads", async () => {
  const res = await call("/listing/%3Cscript%3Ealert(1)%3C%2Fscript%3E");
  assert.equal(res.status, 200);
  const body = await res.text();
  assert.match(body, /camiguinmarket:\/\/listing\/hello%20world/);
  const body2 = await res.text();
  assert.ok(!body2.includes("<script>"), "raw script tag must not appear");
});

test("deep-link fallback percent-encodes special characters", async () => {
  const res = await call("/listing/a%22b%26c");
  assert.equal(res.status, 200);
  const body = await res.text();
  assert.ok(!body.includes('camiguinmarket://listing/a"b'), "raw quote must not appear");
  assert.match(body, /camiguinmarket:\/\/listing\/a%22b%26c/);
});

test("deep-link fallback rejects malformed percent encoding", async () => {
  const res = await call("/listing/%E0%A4%A");
  assert.equal(res.status, 400);
});

test("deep-link fallback rejects empty id", async () => {
  const res = await call("/listing/");
  assert.equal(res.status, 404);
});

test("unknown path returns 404", async () => {
  const res = await call("/foo/bar");
  assert.equal(res.status, 404);
});
