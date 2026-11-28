// The exact requests the deleter sends to Cloudinary and Cloudflare R2.
import assert from "node:assert/strict";
import { ENV, cloudinaryRequest, deleteObject, fakeFetch, ok, r2Request, report, resourceType, row, test } from "./edge-harness.mjs";

await test("r2 deletes through the Cloudflare API, with slashes kept literal", async () => {
  const { fetchImpl, calls } = fakeFetch([ok({ success: true, result: {} })]);
  const result = await deleteObject(row(), ENV, fetchImpl);

  assert.equal(result.ok, true);
  assert.equal(calls.length, 1);
  assert.equal(
    calls[0].url,
    "https://api.cloudflare.com/client/v4/accounts/acc-1/r2/buckets/camiguin-images/objects/listing_images/u1/1700000000-abcd.jpg",
  );
  assert.equal(calls[0].init.method, "DELETE");
  assert.equal(calls[0].init.headers.Authorization, "Bearer cf-token");
});

await test("an r2 key is encoded per segment, so it cannot escape its prefix", async () => {
  const request = r2Request(ENV, row({ storage_key: "listing_images/u1/a b?x=1#y.jpg" }));
  assert.match(request.url, /objects\/listing_images\/u1\/a%20b%3Fx%3D1%23y\.jpg$/);
  assert.ok(!request.url.includes("%2F"), "no encoded slashes");
});

await test("cloudinary deletes through the Admin API with Basic auth", async () => {
  const { fetchImpl, calls } = fakeFetch([ok({ deleted: { "camiguin/products/abc": "deleted" } })]);
  const result = await deleteObject(
    row({
      provider: "cloudinary",
      storage_key: "camiguin/products/abc",
      mime_type: "image/webp",
    }),
    ENV,
    fetchImpl,
  );

  assert.equal(result.ok, true);
  assert.equal(calls[0].url, "https://api.cloudinary.com/v1_1/camiguin/resources/image/upload");
  assert.equal(calls[0].init.method, "DELETE");
  assert.equal(calls[0].init.body, "public_ids[]=camiguin%2Fproducts%2Fabc");
  assert.equal(calls[0].init.headers["Content-Type"], "application/x-www-form-urlencoded");
  assert.equal(calls[0].init.headers.Authorization, `Basic ${btoa("123456789:cloudinary-secret")}`);
});

await test("a video is deleted as a cloudinary video resource", async () => {
  const request = cloudinaryRequest(ENV, row({ provider: "cloudinary", mime_type: "video/mp4" }));
  assert.match(request.url, /\/resources\/video\/upload$/);
  assert.equal(resourceType(row({ mime_type: "video/mp4" })), "video");
  assert.equal(resourceType(row({ mime_type: "image/jpeg" })), "image");
  assert.equal(resourceType({}), "image");
});

report();
