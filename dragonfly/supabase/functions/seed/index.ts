// Loads/updates the question banks from dragonfly/content/seed.json in the repo.
// POST with header x-dragonfly-key. Safe to run again: it upserts by id.
import { createClient } from "npm:@supabase/supabase-js@2";
const SEED_URL = "https://raw.githubusercontent.com/dev-d-14/drill/main/dragonfly/content/seed.json";
const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
async function sha256(s: string) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
Deno.serve(async (req) => {
  const key = (req.headers.get("x-dragonfly-key") ?? "").trim().toUpperCase();
  const { data: row } = await db.from("app_keys").select("key_hash").eq("id", 1).single();
  if (!key || !row || (await sha256(key)) !== row.key_hash) return new Response("no", { status: 401 });
  const seed = await (await fetch(SEED_URL, { cache: "no-store" })).json();
  const a = await db.from("questions").upsert(seed.questions);
  const b = await db.from("interview_questions").upsert(seed.interview);
  return Response.json({ questions: seed.questions.length, interview: seed.interview.length, errors: [a.error?.message, b.error?.message].filter(Boolean) });
});
