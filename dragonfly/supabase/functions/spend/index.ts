// Back-tap endpoint, made for a 3-step iPhone shortcut:
//   GET /functions/v1/spend?k=<setup code>&t=<what she typed>  →  plain text reply for the notification
import { createClient } from "npm:@supabase/supabase-js@2";
import { categorize, parseSpend } from "./categorize.ts";
const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
const TZ = 330;
const text = (s: string, status = 200) => new Response(s, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });
async function sha256(s: string) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
const inr = (n: number) => "₹" + Math.round(n).toLocaleString("en-IN");
Deno.serve(async (req) => {
  const u = new URL(req.url);
  const key = (u.searchParams.get("k") ?? req.headers.get("x-dragonfly-key") ?? "").trim().toUpperCase();
  const { data: row } = await db.from("app_keys").select("key_hash").eq("id", 1).single();
  if (!key || !row || (await sha256(key)) !== row.key_hash) return text("Dragonfly: the code in this shortcut is wrong", 401);
  const said = (u.searchParams.get("t") ?? (req.method === "POST" ? await req.text() : "")).replace(/\+/g, " ");
  const p = parseSpend(said);
  if (!p) return text('Dragonfly: no amount found. Try "250 chai".');
  const learned = Object.fromEntries(((await db.from("learned_words").select("word, category")).data ?? []).map((r: any) => [r.word, r.category]));
  const category = categorize(p.note, learned);
  const ins = await db.from("expenses").insert({ amount: p.amount, note: p.note, category, source: "shortcut" });
  if (ins.error) return text("Dragonfly: couldn't save, try again", 500);
  const now = new Date(Date.now() + TZ * 60000).toISOString();
  const start = new Date(Date.parse(now.slice(0, 7) + "-01T00:00:00Z") - TZ * 60000).toISOString();
  const rows = (await db.from("expenses").select("amount").gte("spent_at", start)).data ?? [];
  const total = rows.reduce((s: number, r: any) => s + Number(r.amount), 0);
  return text(`${inr(p.amount)} · ${category ?? "needs a category"} — ${inr(total)} this month`);
});
