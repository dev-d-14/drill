// Dragonfly API: one endpoint, every call carries the app's setup code in x-dragonfly-key.
import { createClient } from "npm:@supabase/supabase-js@2";
import { categorize, parseSpend, CATEGORIES } from "./categorize.ts";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, x-dragonfly-key",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const TZ_OFFSET_MIN = 330; // India

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}
async function sha256(s: string) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
// India-local calendar helpers
function localNow() { return new Date(Date.now() + TZ_OFFSET_MIN * 60000); }
function localDay(d = localNow()) { return d.toISOString().slice(0, 10); }
function startOfLocalDayUTC(day: string) { return new Date(Date.parse(day + "T00:00:00Z") - TZ_OFFSET_MIN * 60000); }
function monthBoundsUTC(ym: string) {
  const [y, m] = ym.split("-").map(Number);
  const start = startOfLocalDayUTC(`${ym}-01`);
  const next = m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, "0")}-01`;
  return { start, end: startOfLocalDayUTC(next) };
}
function addDays(day: string, n: number) {
  const d = new Date(day + "T00:00:00Z"); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10);
}
function dayOfYear(day: string) {
  const d = new Date(day + "T00:00:00Z");
  return Math.floor((d.getTime() - Date.UTC(d.getUTCFullYear(), 0, 1)) / 86400000);
}
function must<T>(r: { data: T; error: unknown }): T {
  if (r.error) throw r.error;
  return r.data;
}
function inr(n: number) { return "₹" + Math.round(n).toLocaleString("en-IN"); }

// ---------- money ----------
async function learnedMap() {
  const rows = must(await db.from("learned_words").select("word, category")) as { word: string; category: string }[];
  return Object.fromEntries(rows.map((r) => [r.word, r.category]));
}
async function monthTotal() {
  const { start, end } = monthBoundsUTC(localDay().slice(0, 7));
  const rows = must(await db.from("expenses").select("amount").gte("spent_at", start.toISOString()).lt("spent_at", end.toISOString())) as { amount: number }[];
  return rows.reduce((s, r) => s + Number(r.amount), 0);
}
async function spend(text: string, source: string) {
  const p = parseSpend(text ?? "");
  if (!p) return { ok: false, message: "Couldn't find an amount. Try \"250 chai\"." };
  const category = categorize(p.note, await learnedMap());
  const row = must(await db.from("expenses").insert({ amount: p.amount, note: p.note, category, source }).select().single());
  const total = await monthTotal();
  const message = `${inr(p.amount)} · ${category ?? "needs a category"} — ${inr(total)} this month`;
  return { ok: true, message, expense: row, monthTotal: total };
}
async function moneyMonth(ym: string) {
  const { start, end } = monthBoundsUTC(ym);
  const rows = must(await db.from("expenses").select("*").gte("spent_at", start.toISOString()).lt("spent_at", end.toISOString()).order("spent_at", { ascending: false })) as any[];
  const total = rows.reduce((s, r) => s + Number(r.amount), 0);
  const byCat: Record<string, number> = {};
  for (const r of rows) byCat[r.category ?? "Needs a category"] = (byCat[r.category ?? "Needs a category"] ?? 0) + Number(r.amount);
  // same point last month, for comparison
  const today = localDay();
  const isCurrent = today.slice(0, 7) === ym;
  const dayN = isCurrent ? Number(today.slice(8, 10)) : new Date(end.getTime() - 1).getUTCDate();
  const [y, m] = ym.split("-").map(Number);
  const prevYm = m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, "0")}`;
  const pb = monthBoundsUTC(prevYm);
  const prevCut = new Date(Math.min(pb.start.getTime() + dayN * 86400000, pb.end.getTime()));
  const prevRows = must(await db.from("expenses").select("amount").gte("spent_at", pb.start.toISOString()).lt("spent_at", prevCut.toISOString())) as any[];
  const prevSoFar = prevRows.reduce((s, r) => s + Number(r.amount), 0);
  // last 7 days (current month view) — always relative to today
  const days: { day: string; total: number }[] = [];
  const from = startOfLocalDayUTC(addDays(today, -6));
  const wk = must(await db.from("expenses").select("amount, spent_at").gte("spent_at", from.toISOString())) as any[];
  for (let i = 6; i >= 0; i--) {
    const d = addDays(today, -i);
    const s = startOfLocalDayUTC(d).getTime(), e = s + 86400000;
    days.push({ day: d, total: wk.filter((r) => { const t = Date.parse(r.spent_at); return t >= s && t < e; }).reduce((a, r) => a + Number(r.amount), 0) });
  }
  return { ym, total, perDay: total / Math.max(1, dayN), prevSoFar, hasPrev: prevRows.length > 0, byCat, entries: rows, last7: days, today: days[6].total };
}

// ---------- study ----------
const SLOT_SUBJECT = (day: string) => (dayOfYear(day) % 2 === 0 ? "gnav" : "rnav");
async function pickFor(day: string, slot: "regs" | "nav") {
  const existing = must(await db.from("daily_pick").select("qid").eq("day", day).eq("slot", slot).maybeSingle()) as any;
  if (existing) return existing.qid as string;
  const subject = slot === "regs" ? "regs" : SLOT_SUBJECT(day);
  const qid = await nextUnseen(subject);
  if (!qid) return null;
  must(await db.from("daily_pick").insert({ day, slot, qid }));
  return qid;
}
async function nextUnseen(subject: string, exclude: string[] = []) {
  const seen = (must(await db.from("qstate").select("qid")) as any[]).map((r) => r.qid);
  const picked = (must(await db.from("daily_pick").select("qid")) as any[]).map((r) => r.qid);
  const skip = new Set([...seen, ...picked, ...exclude]);
  const qs = must(await db.from("questions").select("id").eq("subject", subject).order("ord")) as any[];
  const q = qs.find((x) => !skip.has(x.id));
  return q ? q.id as string : null;
}
async function getQuestions(ids: string[]) {
  if (!ids.length) return [];
  const qs = must(await db.from("questions").select("*").in("id", ids)) as any[];
  const st = must(await db.from("qstate").select("*").in("qid", ids)) as any[];
  const todayStart = startOfLocalDayUTC(localDay()).toISOString();
  const att = must(await db.from("attempts").select("qid, chosen, correct, at").in("qid", ids).gte("at", todayStart).order("at", { ascending: false })) as any[];
  return ids.map((id) => {
    const q = qs.find((x) => x.id === id);
    if (!q) return null;
    const a = att.find((x) => x.qid === id);
    return { ...q, state: st.find((x) => x.qid === id) ?? null, answeredToday: a ? { chosen: a.chosen, correct: a.correct } : null };
  }).filter(Boolean);
}
const BOX_DAYS = [0, 3, 7, 16, 35];
async function answer(qid: string, chosen: number) {
  const q = must(await db.from("questions").select("answer").eq("id", qid).single()) as any;
  const correct = q.answer === chosen;
  must(await db.from("attempts").insert({ qid, chosen, correct }));
  const st = must(await db.from("qstate").select("*").eq("qid", qid).maybeSingle()) as any;
  let box: number, due: string | null;
  if (!correct) { box = 1; due = addDays(localDay(), 3); }            // wrong: back in 3 days
  else if (!st || st.box === 0 || st.box >= 4) { box = 4; due = null; } // right first time: learned
  else { box = st.box + 1; due = box >= 4 ? null : addDays(localDay(), BOX_DAYS[box]); } // in review: 7, then 16 days
  must(await db.from("qstate").upsert({ qid, box, due_at: due ? startOfLocalDayUTC(due).toISOString() : null, last_correct: correct }));
  return { correct, answer: q.answer, dueDay: due };
}
async function reviewDue() {
  const now = new Date(startOfLocalDayUTC(addDays(localDay(), 1)).getTime() - 1).toISOString();
  const rows = must(await db.from("qstate").select("qid").not("due_at", "is", null).lte("due_at", now)) as any[];
  return rows.map((r) => r.qid as string);
}
async function progress() {
  const qs = must(await db.from("questions").select("id, subject, topic")) as any[];
  const st = must(await db.from("qstate").select("qid, last_correct, due_at")) as any[];
  const out: Record<string, any> = {};
  for (const s of ["regs", "gnav", "rnav"]) {
    const ids = new Set(qs.filter((q) => q.subject === s).map((q) => q.id));
    const mine = st.filter((x) => ids.has(x.qid));
    out[s] = { total: ids.size, seen: mine.length, correct: mine.filter((x) => x.last_correct).length, toReview: mine.filter((x) => x.due_at).length };
  }
  // weakest topics: most wrong attempts
  const att = must(await db.from("attempts").select("qid, correct")) as any[];
  const wrongByTopic: Record<string, number> = {};
  for (const a of att) if (!a.correct) { const q = qs.find((x) => x.id === a.qid); if (q) { const k = `${q.subject}|${q.topic}`; wrongByTopic[k] = (wrongByTopic[k] ?? 0) + 1; } }
  const weak = Object.entries(wrongByTopic).sort((a, b) => b[1] - a[1]).slice(0, 4).map(([k, n]) => ({ subject: k.split("|")[0], topic: k.split("|")[1], wrong: n }));
  return { subjects: out, weak };
}

// ---------- interview ----------
async function interviewToday() {
  const qs = must(await db.from("interview_questions").select("*").order("ord")) as any[];
  if (!qs.length) return null;
  return qs[dayOfYear(localDay()) % qs.length];
}

// ---------- today ----------
async function today() {
  const day = localDay();
  const startToday = startOfLocalDayUTC(day).toISOString();
  const regs = await pickFor(day, "regs");
  const nav = await pickFor(day, "nav");
  const att = must(await db.from("attempts").select("qid").gte("at", startToday)) as any[];
  const rec = must(await db.from("recordings").select("id").gte("at", startToday)) as any[];
  const calm = must(await db.from("calm_sessions").select("kind, holds, seconds").gte("at", startToday).order("at", { ascending: false })) as any[];
  const weekStart = addDays(day, -((new Date(day + "T00:00:00Z").getUTCDay() + 6) % 7)); // Monday
  const knee = (must(await db.from("knee_days").select("day").gte("day", weekStart)) as any[]).map((r) => r.day);
  const exams = must(await db.from("exams").select("*").gte("exam_date", day).order("exam_date")) as any[];
  const money = await moneyMonth(day.slice(0, 7));
  const topCat = Object.entries(money.byCat).sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
  const iq = await interviewToday();
  return {
    day, weekStart, knee,
    navSubject: SLOT_SUBJECT(day),
    nextNavSubject: SLOT_SUBJECT(addDays(day, 1)),
    regsDone: !!regs && att.some((a) => a.qid === regs),
    navDone: !!nav && att.some((a) => a.qid === nav),
    regsTopic: regs ? (must(await db.from("questions").select("topic").eq("id", regs).single()) as any).topic : null,
    navTopic: nav ? (must(await db.from("questions").select("topic").eq("id", nav).single()) as any).topic : null,
    interviewDone: rec.length > 0, interviewKind: iq?.kind ?? null,
    calmDone: calm.length > 0, calmLast: calm[0] ?? null,
    exams,
    money: { total: money.total, today: money.today, topCat, last7: money.last7 },
  };
}

// ---------- router ----------
Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const key = (req.headers.get("x-dragonfly-key") ?? "").trim().toUpperCase();
    const row = must(await db.from("app_keys").select("key_hash").eq("id", 1).single()) as any;
    if (!key || (await sha256(key)) !== row.key_hash) return json({ ok: false, message: "Wrong setup code" }, 401);

    const body = await req.json().catch(() => ({}));
    const a = body.action as string;
    switch (a) {
      case "ping": return json({ ok: true });
      case "spend": return json(await spend(body.text, body.source ?? "shortcut"));
      case "today": return json(await today());
      case "money": return json(await moneyMonth(body.ym ?? localDay().slice(0, 7)));
      case "categories": return json({ categories: CATEGORIES });
      case "expense.update": {
        const patch: Record<string, unknown> = {};
        if (body.amount != null) patch.amount = Number(body.amount);
        if (body.note != null) patch.note = String(body.note);
        if ("category" in body) patch.category = body.category;
        const e = must(await db.from("expenses").update(patch).eq("id", body.id).select().single()) as any;
        if (body.remember && body.category && e.note) {
          const words = String(e.note).toLowerCase().replace(/[^a-z0-9&\s]/g, " ").split(/\s+/).filter((w) => w.length > 1 && !/^\d+$/.test(w));
          if (words.length) must(await db.from("learned_words").upsert(words.map((w) => ({ word: w, category: body.category }))));
        }
        return json({ ok: true, expense: e });
      }
      case "expense.delete": must(await db.from("expenses").delete().eq("id", body.id)); return json({ ok: true });
      case "learned.list": return json({ words: must(await db.from("learned_words").select("*").order("word")) });
      case "learned.delete": must(await db.from("learned_words").delete().eq("word", body.word)); return json({ ok: true });

      case "study.today": {
        const day = localDay();
        const ids = [await pickFor(day, "regs"), await pickFor(day, "nav")].filter(Boolean) as string[];
        const due = await reviewDue();
        return json({ questions: await getQuestions(ids), reviewCount: due.filter((d) => !ids.includes(d)).length, navSubject: SLOT_SUBJECT(day) });
      }
      case "study.more": {
        const id = await nextUnseen(body.subject, body.exclude ?? []);
        if (id) must(await db.from("daily_pick").insert({ day: localDay(), slot: `extra-${id}`, qid: id }));
        return json({ questions: id ? await getQuestions([id]) : [] });
      }
      case "study.review": return json({ questions: await getQuestions(await reviewDue()) });
      case "study.answer": return json(await answer(body.qid, Number(body.chosen)));
      case "study.progress": return json(await progress());

      case "interview.today": {
        const q = await interviewToday();
        const recs = must(await db.from("recordings").select("*").order("at", { ascending: false }).limit(20)) as any[];
        const withUrls = await Promise.all(recs.map(async (r) => {
          const { data } = await db.storage.from("recordings").createSignedUrl(r.path, 3600);
          return { ...r, url: data?.signedUrl ?? null };
        }));
        return json({ question: q, recordings: withUrls });
      }
      case "interview.another": {
        const qs = must(await db.from("interview_questions").select("*").eq("kind", body.kind)) as any[];
        const pool = qs.filter((q) => q.id !== body.not);
        return json({ question: pool[Math.floor(Math.random() * pool.length)] ?? null });
      }
      case "interview.uploadUrl": {
        const path = `${localDay()}/${crypto.randomUUID()}.${body.ext === "webm" ? "webm" : "m4a"}`;
        const { data, error } = await db.storage.from("recordings").createSignedUploadUrl(path);
        if (error) throw error;
        return json({ path, token: data.token, signedUrl: data.signedUrl });
      }
      case "interview.save": {
        const r = must(await db.from("recordings").insert({ qid: body.qid ?? null, question: body.question, path: body.path, seconds: Math.round(body.seconds ?? 0) }).select().single());
        return json({ ok: true, recording: r });
      }
      case "interview.delete": {
        const r = must(await db.from("recordings").select("path").eq("id", body.id).single()) as any;
        await db.storage.from("recordings").remove([r.path]);
        must(await db.from("recordings").delete().eq("id", body.id));
        return json({ ok: true });
      }

      case "calm.save": must(await db.from("calm_sessions").insert({ kind: body.kind, seconds: Math.round(body.seconds ?? 0), holds: body.holds ?? [] })); return json({ ok: true });
      case "calm.recent": return json({ sessions: must(await db.from("calm_sessions").select("*").order("at", { ascending: false }).limit(10)) });

      case "knee.toggle": {
        const day = body.day ?? localDay();
        const has = must(await db.from("knee_days").select("day").eq("day", day).maybeSingle());
        if (has) must(await db.from("knee_days").delete().eq("day", day)); else must(await db.from("knee_days").insert({ day }));
        return json({ ok: true, done: !has });
      }

      case "exams.list": return json({ exams: must(await db.from("exams").select("*").order("exam_date")) });
      case "exams.add": return json({ exam: must(await db.from("exams").insert({ paper: body.paper, exam_date: body.date }).select().single()) });
      case "exams.delete": must(await db.from("exams").delete().eq("id", body.id)); return json({ ok: true });

      case "export": {
        const t = ["expenses", "learned_words", "attempts", "qstate", "recordings", "calm_sessions", "knee_days", "exams"];
        const out: Record<string, unknown> = { exported_at: new Date().toISOString() };
        for (const name of t) out[name] = must(await db.from(name).select("*"));
        return json(out);
      }
      default: return json({ ok: false, message: "Unknown action" }, 400);
    }
  } catch (e) {
    console.error(e);
    return json({ ok: false, message: "Something went wrong", detail: String((e as any)?.message ?? e) }, 500);
  }
});
