/**
 * The small HTTP helpers the deleter needs: a timeout signal, a bounded body
 * read, lenient JSON parsing and a message out of a thrown value. No Deno or Node
 * APIs, so they behave the same in the Edge Function and in the tests.
 */
import { TIMEOUT_MS } from "./providers.js";

export function timeoutSignal() {
  if (typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function") {
    return AbortSignal.timeout(TIMEOUT_MS);
  }
  return undefined;
}

export async function safeText(response) {
  try {
    return await response.text();
  } catch {
    return "";
  }
}

export function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function message(error) {
  if (!error) return "unknown error";
  return error.message ? String(error.message) : String(error);
}
