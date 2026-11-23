// Her spending categories: list, add, rename, delete. Same setup-code check as the api function.
import { createClient } from "npm:@supabase/supabase-js@2";
const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "content-type, x-dragonfly-key", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { ...CORS, "Content-Type": "application/json" } });
async function sha256(s: string) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
const clean = (s: unknown) => String(s ?? "").replace(/\s+/g, " ").trim().slice(0, 24);
async function list() {
  const { data } = await db.from("categories").select("name, ord").order("ord").order("created_at");
  return (data ?? []).map((r) => r.name);
}
Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const key = (req.headers.get("x-dragonfly-key") ?? "").trim().toUpperCase();
  const { data: row } = await db.from("app_keys").select("key_hash").eq("id", 1).single();
  if (!key || !row || (await sha256(key)) !== row.key_hash) return json({ ok: false, message: "Wrong setup code" }, 401);
  const b = await req.json().catch(() => ({}));
  if (b.action === "add") {
    const name = clean(b.name);
    if (!name) return json({ ok: false, message: "Give the category a name" }, 400);
    const all = await list();
    if (all.some((c) => c.toLowerCase() === name.toLowerCase())) return json({ ok: false, message: `${name} already exists` }, 400);
    await db.from("categories").insert({ name, ord: 50 + all.length });
  } else if (b.action === "rename") {
    const from = clean(b.from), to = clean(b.to);
    if (!from || !to) return json({ ok: false, message: "Give the category a name" }, 400);
    if ((await list()).some((c) => c.toLowerCase() === to.toLowerCase() && c !== from)) return json({ ok: false, message: `${to} already exists` }, 400);
    const { data: old } = await db.from("categories").select("ord").eq("name", from).single();
    await db.from("categories").insert({ name: to, ord: old?.ord ?? 50 });
    await db.from("expenses").update({ category: to }).eq("category", from);
    await db.from("learned_words").update({ category: to }).eq("category", from);
    await db.from("categories").delete().eq("name", from);
  } else if (b.action === "delete") {
    const name = clean(b.name);
    // spends in it go back to "to sort"; words learned for it are forgotten
    await db.from("expenses").update({ category: null }).eq("category", name);
    await db.from("learned_words").delete().eq("category", name);
    await db.from("categories").delete().eq("name", name);
  }
  return json({ ok: true, categories: await list() });
});
