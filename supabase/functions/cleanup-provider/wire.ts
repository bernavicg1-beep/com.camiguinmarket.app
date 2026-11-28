/**
 * The Supabase side of the function: the two calls it is allowed to make — read
 * the queued rows, and report one result — plus the JSON response helper.
 */
import { createClient } from "npm:@supabase/supabase-js@2";

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

type PendingRow = {
  id: string;
  seller_id: string;
  provider: "cloudinary" | "r2";
  storage_key: string;
  public_url: string;
  mime_type: string | null;
  bytes: number | string;
  queued_at: string;
};

/** The two database calls this function is allowed to make. */
export function pendingClient(supabase: ReturnType<typeof createClient>) {
  return {
    async listPending(limit: number): Promise<PendingRow[]> {
      const { data, error } = await supabase
        .from("cleanup_pending_deletions")
        .select("id, seller_id, provider, storage_key, public_url, mime_type, bytes, queued_at")
        .limit(limit);
      if (error) throw new Error(`list pending: ${error.message}`);
      return (data ?? []) as PendingRow[];
    },

    async confirm(mediaId: string, ok: boolean, error: string | null): Promise<void> {
      const { error: rpcError } = await supabase.rpc("cleanup_confirm", {
        p_media_id: mediaId,
        p_ok: ok,
        p_error: error,
      });
      if (rpcError) throw new Error(`confirm ${mediaId}: ${rpcError.message}`);
    },
  };
}
