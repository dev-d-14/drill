// Dragonfly — Hasini's daily app. Plain JS, no build step.
const API = "https://focgmnxwdosljubcbsgw.supabase.co/functions/v1/api";
const NAME = "Hasini";
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const app = $("#app");

// ---------- storage (iOS can throw in odd modes) ----------
const store = {
  get(k, d = null) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
  del(k) { try { localStorage.removeItem(k); } catch {} },
};
let KEY = store.get("df.key");

// ---------- helpers ----------
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const inr = (n) => "₹" + Math.round(Number(n) || 0).toLocaleString("en-IN");
const SUBJ = { regs: "Air Regs", gnav: "General Nav", rnav: "Radio Nav", met: "Met" };
const SUBJ_LONG = { regs: "Air Regulations", gnav: "General Navigation", rnav: "Radio Navigation", met: "Meteorology" };
const KIND = { scenario: "Scenario", technical: "Technical", airline: "Airline & HR" };
const CATS = ["Food", "Transport", "Shopping", "Bills", "Fun", "Gifts", "Health", "Study", "Other"];
function localISO(d = new Date()) { const z = (n) => String(n).padStart(2, "0"); return `${d.getFullYear()}-${z(d.getMonth() + 1)}-${z(d.getDate())}`; }
function daysUntil(iso) { return Math.round((Date.parse(iso + "T00:00:00") - Date.parse(localISO() + "T00:00:00")) / 864e5); }
function niceDate(iso) { return new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" }); }
function whenLabel(ts) {
  const d = new Date(ts), day = localISO(d), t = d.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" });
  if (day === localISO()) return t;
  const y = new Date(); y.setDate(y.getDate() - 1);
  if (day === localISO(y)) return "Yesterday";
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}
const mmss = (s) => { s = Math.max(0, Math.round(s)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
function toast(msg, ms = 2600) { const t = $("#toast"); t.textContent = msg; t.hidden = false; clearTimeout(toast._t); toast._t = setTimeout(() => (t.hidden = true), ms); }
const spinner = () => `<div class="spin" aria-label="Loading"></div>`;

async function api(action, data = {}) {
  let r;
  try {
    r = await fetch(API, { method: "POST", headers: { "content-type": "application/json", "x-dragonfly-key": KEY ?? "" }, body: JSON.stringify({ action, ...data }) });
  } catch { throw new Error("No internet — try again"); }
  const j = await r.json().catch(() => ({}));
  if (r.status === 401) { store.del("df.key"); KEY = null; location.hash = "#/setup"; throw new Error("locked"); }
  if (!r.ok || j.ok === false) throw new Error(j.message || "Something went wrong");
  return j;
}
function fail(e) { if (e.message !== "locked") toast(e.message); }

// ---------- little drawings ----------
const DRAGONFLY = `<svg width="84" height="84" viewBox="0 0 120 120" aria-hidden="true" fill="none" stroke="#D8B65C" stroke-width="1.6">
<ellipse cx="36" cy="44" rx="27" ry="9" transform="rotate(-14 36 44)" fill="rgba(216,182,92,.14)"/><ellipse cx="84" cy="44" rx="27" ry="9" transform="rotate(14 84 44)" fill="rgba(216,182,92,.14)"/>
<ellipse cx="39" cy="60" rx="22" ry="7.5" transform="rotate(12 39 60)" fill="rgba(216,182,92,.14)"/><ellipse cx="81" cy="60" rx="22" ry="7.5" transform="rotate(-12 81 60)" fill="rgba(216,182,92,.14)"/>
<path d="M14 47 Q36 40 58 45 M106 47 Q84 40 62 45 M20 61 Q39 60 58 55 M100 61 Q81 60 62 55" stroke-dasharray="2 3"/>
<circle cx="55" cy="27" r="5"/><circle cx="65" cy="27" r="5"/><path d="M60 33 L60 108 M56 70 L64 70 M56 80 L64 80 M56 90 L64 90 M57 100 L63 100"/></svg>`;
const GEAR = `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M2 12h3M19 12h3M4.9 19.1L7 17M17 7l2.1-2.1"/></svg>`;
const BACK = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg>`;
function bars(list, h = 52) {
  const max = Math.max(1, ...list.map((d) => d.total));
  return `<div class="bars" style="height:${h}px" aria-hidden="true">${list.map((d, i) => `<span class="${i === list.length - 1 ? "today" : ""}" style="height:${Math.max(4, (d.total / max) * 100)}%"></span>`).join("")}</div>`;
}

// ---------- router ----------
const views = { setup, today, money, study, interview, calm, exams, settings };
let cleanup = null;
async function route() {
  if (cleanup) { try { cleanup(); } catch {} cleanup = null; }
  let name = (location.hash.replace(/^#\//, "").split("?")[0] || "today");
  if (!KEY) name = "setup";
  if (!views[name]) name = "today";
  $("#tabs").hidden = name === "setup";
  $$("#tabs a").forEach((a) => a.classList.toggle("on", a.dataset.tab === name));
  closeSheet();
  window.scrollTo(0, 0);
  try { await views[name](); } catch (e) { fail(e); }
}
window.addEventListener("hashchange", route);
document.addEventListener("visibilitychange", () => { if (!document.hidden && /today|money/.test(location.hash || "#/today") && !$(".sheet-bg")) route(); });

// ---------- setup ----------
async function setup() {
  app.innerHTML = `
  <div style="display:flex;flex-direction:column;align-items:center;gap:10px;margin-top:40px;text-align:center">
    ${DRAGONFLY.replace('width="84" height="84"', 'width="120" height="120"')}
    <h1>Welcome, ${NAME}</h1>
    <p class="mut" style="max-width:280px;margin:0">Type the setup code Dharmik gave you. You only need to do this once on this phone.</p>
  </div>
  <form id="f" class="card" style="display:flex;flex-direction:column;gap:12px;margin-top:10px">
    <div class="field"><label for="code">Setup code</label>
    <input id="code" class="input" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="DF-XXXXX-XXXXX-XXXXX-XXXXX" style="letter-spacing:.05em"></div>
    <button class="btn wide">Open Dragonfly</button>
    <div id="err" class="mut" role="alert"></div>
  </form>`;
  $("#f").onsubmit = async (e) => {
    e.preventDefault();
    const v = $("#code").value.trim().toUpperCase();
    if (!v) return;
    KEY = v;
    try { await api("ping"); store.set("df.key", v); location.hash = "#/today"; route(); }
    catch { KEY = null; $("#err").textContent = "That code didn't work. Check it and try again."; }
  };
}

// ---------- today ----------
async function today() {
  app.innerHTML = spinner();
  const d = await api("today");
  const hr = new Date().getHours();
  const greet = hr < 12 ? "Good morning" : hr < 17 ? "Good afternoon" : "Good evening";
  const done = [d.regsDone, d.navDone, d.interviewDone, d.calmDone, d.knee.includes(d.day)].filter(Boolean).length;
  const week = Array.from({ length: 7 }, (_, i) => { const x = new Date(d.weekStart + "T00:00:00"); x.setDate(x.getDate() + i); return localISO(x); });
  const chips = d.exams.slice(0, 3).map((x) => { const n = daysUntil(x.exam_date); return `<a class="chip" href="#/exams">${esc(x.paper)} · ${n === 0 ? "<b>today</b>" : `<b>${n}</b> day${n === 1 ? "" : "s"}`}</a>`; }).join("");
  const item = (on, title, sub, href) => `<a class="check" href="${href}"><span class="dot ${on ? "on" : ""}"></span><div style="flex:1"><div>${title}</div><div class="mut">${sub}</div></div></a>`;
  app.innerHTML = `
  <div class="row-between">
    <div><div class="mut">${new Date().toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "long" })}</div>
    <h1 style="margin-top:2px">${greet},<br>${NAME}</h1></div>
    <div style="display:flex;flex-direction:column;align-items:flex-end;gap:4px">
      <a class="iconbtn" href="#/settings" aria-label="Settings">${GEAR}</a>
      ${DRAGONFLY.replace('width="84" height="84"', 'width="70" height="70"')}
    </div>
  </div>
  <div class="chips">${chips || ""}<a class="chip" href="#/exams">${d.exams.length ? "+ exam" : "+ Add exam countdown"}</a></div>
  <a class="card row-between" href="#/money">
    <div><div class="lab">Spent this month</div>
    <div class="big" style="font-size:42px">${inr(d.money.total)}</div>
    <div class="mut">${inr(d.money.today)} today${d.money.topCat && d.money.total ? ` · most on ${esc(d.money.topCat)}` : ""}</div></div>
    <div style="width:84px">${bars(d.money.last7, 52)}</div>
  </a>
  <div class="card" style="padding:12px 16px">
    <div class="lab" style="margin:4px 0 2px">Today's flight plan · ${done} of 5</div>
    ${item(d.regsDone, "Air Regs question", esc(d.regsTopic ?? "All questions done"), "#/study")}
    ${item(d.navDone, "Nav question", `${SUBJ[d.navSubject]} today · ${SUBJ[d.nextNavSubject]} tomorrow`, "#/study")}
    ${item(d.interviewDone, "2-minute answer", d.interviewKind ? KIND[d.interviewKind] + " question" : "Interview practice", "#/interview")}
    ${item(d.calmDone, "Breathe", d.calmLast ? (d.calmLast.kind === "wimhof" ? `Wim Hof · ${d.calmLast.holds.length} round${d.calmLast.holds.length === 1 ? "" : "s"}` : `Timer · ${Math.round(d.calmLast.seconds / 60)} min`) : "Wim Hof or a quiet timer", "#/calm")}
    <div class="check" style="cursor:default"><button class="dot ${d.knee.includes(d.day) ? "on" : ""}" id="kneeDot" aria-label="Knee exercises done today"></button>
      <div style="flex:1"><div>Knee exercises</div>
      <div class="week" role="img" aria-label="Done on ${d.knee.length} days this week">${week.map((w) => `<i class="${d.knee.includes(w) ? "on" : ""} ${w > d.day ? "future" : ""}"></i>`).join("")}</div></div>
      <button class="btn small ${d.knee.includes(d.day) ? "ghost" : ""}" id="knee">${d.knee.includes(d.day) ? "Undo" : "Done"}</button></div>
  </div>`;
  const kneeToggle = async () => { try { await api("knee.toggle"); today(); } catch (e) { fail(e); } };
  $("#knee").onclick = kneeToggle; $("#kneeDot").onclick = kneeToggle;
}

// ---------- money ----------
let moneyYm = null;
async function money() {
  const cur = localISO().slice(0, 7);
  moneyYm = moneyYm ?? cur;
  app.innerHTML = spinner();
  const m = await api("money", { ym: moneyYm });
  const [y, mo] = moneyYm.split("-").map(Number);
  const title = new Date(y, mo - 1, 1).toLocaleDateString("en-IN", { month: "long", ...(y !== new Date().getFullYear() ? { year: "numeric" } : {}) });
  const isCur = moneyYm === cur;
  const diff = m.total - m.prevSoFar;
  const compare = m.hasPrev ? ` · ${inr(Math.abs(diff))} ${diff <= 0 ? "less" : "more"} than this time last month` : "";
  const cats = Object.entries(m.byCat).sort((a, b) => (a[0] === "Needs a category") - (b[0] === "Needs a category") || b[1] - a[1]);
  const needs = m.entries.filter((e) => !e.category).length;
  app.innerHTML = `
  <div class="row-between">
    <div style="display:flex;align-items:center;gap:6px">
      <button class="iconbtn" id="prev" aria-label="Previous month">${BACK}</button>
      <h1>${title}</h1>
      <button class="iconbtn" id="next" aria-label="Next month" ${isCur ? "disabled style='opacity:.3'" : ""}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg></button>
    </div>
    <button class="btn" id="add">+ Add</button>
  </div>
  <div class="card">
    <div class="lab">Spent ${isCur ? "this month" : "in " + title}</div>
    <div class="big" style="font-size:48px">${inr(m.total)}</div>
    <div class="mut">${inr(m.perDay)} a day${compare}</div>
    ${isCur ? `<div style="margin-top:14px">${bars(m.last7, 60)}</div><div class="mut row-between" style="margin-top:4px"><span>7 days ago</span><span>Today ${inr(m.today)}</span></div>` : ""}
  </div>
  ${cats.length ? `<div class="card" style="display:flex;flex-direction:column;gap:10px">
    ${cats.map(([c, v]) => `<div class="cat"><span class="n">${esc(c)}</span><div class="track"><span style="width:${(v / m.total) * 100}%"></span></div><span class="v">${inr(v)}</span></div>`).join("")}
  </div>` : ""}
  <div class="card" style="padding-bottom:8px">
    <div class="row-between"><span class="lab">${isCur ? "Recent" : "All spends"}</span><span class="mut">${needs ? `${needs} need${needs === 1 ? "s" : ""} a category` : ""}</span></div>
    <div style="margin-top:6px" id="ents">
    ${m.entries.length ? m.entries.map((e) => `<button class="ent" data-id="${e.id}">
      <span class="tile ${e.category ? "" : "q"}">${e.category ? esc(e.category[0]) : "?"}</span>
      <div style="flex:1;min-width:0"><div style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(e.note)}</div><div class="mut">${e.category ? esc(e.category) : "Tap to pick a category"} · ${whenLabel(e.spent_at)}</div></div>
      <span class="amt">${inr(e.amount)}</span></button>`).join("") : `<div class="empty">Nothing yet. Double-tap the back of your phone, or tap + Add.</div>`}
    </div>
  </div>`;
  $("#prev").onclick = () => { const d = new Date(y, mo - 2, 1); moneyYm = localISO(d).slice(0, 7); money(); };
  $("#next").onclick = () => { if (isCur) return; const d = new Date(y, mo, 1); moneyYm = localISO(d).slice(0, 7); money(); };
  $("#add").onclick = addSheet;
  $$("#ents .ent").forEach((b) => (b.onclick = () => editSheet(m.entries.find((e) => String(e.id) === b.dataset.id))));
}
function openSheet(html) {
  const root = $("#sheet-root");
  root.innerHTML = `<div class="sheet-bg"><div class="sheet" role="dialog" aria-modal="true"><div class="grab"></div>${html}</div></div>`;
  $(".sheet-bg").onclick = (e) => { if (e.target.classList.contains("sheet-bg")) closeSheet(); };
  return $(".sheet");
}
function closeSheet() { $("#sheet-root").innerHTML = ""; }
function addSheet() {
  const s = openSheet(`<div class="serif" style="font-size:26px;font-weight:600">Spent?</div>
    <form id="af" style="display:flex;flex-direction:column;gap:12px">
    <input class="input" id="at" placeholder="250 chai" aria-label="What you spent" autocomplete="off" enterkeyhint="done">
    <div class="mut">Amount and a word or two, in any order: "uber 180", "1.2k zara".</div>
    <button class="btn wide">Save</button></form>`);
  setTimeout(() => $("#at", s).focus(), 50);
  $("#af", s).onsubmit = async (e) => {
    e.preventDefault();
    const text = $("#at", s).value.trim(); if (!text) return;
    try { const r = await api("spend", { text, source: "app" }); closeSheet(); toast(r.message); money(); } catch (err) { fail(err); }
  };
}
function editSheet(e) {
  let cat = e.category;
  const s = openSheet(`
    <div class="row-between"><div class="serif" style="font-size:26px;font-weight:600">Edit</div><div class="mut">${whenLabel(e.spent_at)}</div></div>
    <div style="display:grid;grid-template-columns:1fr 1.4fr;gap:10px">
      <div class="field"><label for="ea">Amount (₹)</label><input id="ea" class="input" inputmode="decimal" value="${Number(e.amount)}"></div>
      <div class="field"><label for="en">Note</label><input id="en" class="input" value="${esc(e.note)}"></div>
    </div>
    <div><div class="lab" style="margin-bottom:8px">Category</div>
    <div class="catgrid">${CATS.map((c) => `<button type="button" data-c="${c}" class="${c === cat ? "on" : ""}">${c}</button>`).join("")}</div></div>
    <div class="row-between" style="border:1px solid var(--stitch);border-radius:12px;padding:12px">
      <div><div style="font-weight:600;font-size:14px" id="remT">Remember this</div><div class="mut">Next time it's sorted automatically</div></div>
      <button type="button" class="toggle" role="switch" id="rem" aria-checked="${!e.category}" aria-labelledby="remT"></button></div>
    <div style="display:flex;gap:10px"><button class="btn ghost" id="del" style="flex:1;border-radius:14px;min-height:50px">Delete</button><button class="btn" id="save" style="flex:2;border-radius:14px;min-height:50px">Save</button></div>`);
  const remT = () => { $("#remT", s).textContent = cat && $("#en", s).value.trim() ? `Remember "${$("#en", s).value.trim().split(/\s+/)[0]}" → ${cat}` : "Remember this"; };
  remT();
  $$(".catgrid button", s).forEach((b) => (b.onclick = () => { cat = b.dataset.c; $$(".catgrid button", s).forEach((x) => x.classList.toggle("on", x === b)); if (cat !== e.category) $("#rem", s).setAttribute("aria-checked", "true"); remT(); }));
  $("#en", s).oninput = remT;
  $("#rem", s).onclick = () => { const t = $("#rem", s); t.setAttribute("aria-checked", t.getAttribute("aria-checked") === "true" ? "false" : "true"); };
  $("#del", s).onclick = async () => { if (!confirm(`Delete ${inr(e.amount)} ${e.note}?`)) return; try { await api("expense.delete", { id: e.id }); closeSheet(); toast("Deleted"); money(); } catch (err) { fail(err); } };
  $("#save", s).onclick = async () => {
    const amount = parseFloat($("#ea", s).value.replace(/[^\d.]/g, ""));
    if (!(amount > 0)) return toast("Check the amount");
    try {
      await api("expense.update", { id: e.id, amount, note: $("#en", s).value.trim(), category: cat ?? null, remember: $("#rem", s).getAttribute("aria-checked") === "true" });
      closeSheet(); toast("Saved"); money();
    } catch (err) { fail(err); }
  };
}

// ---------- study ----------
let studyTab = "today";
async function study() {
  app.innerHTML = `<h1>Study</h1>
  <div class="seg" role="tablist"><button data-t="today">Today</button><button data-t="review">Review</button><button data-t="progress">Progress</button></div>
  <div id="sbody">${spinner()}</div>`;
  $$(".seg button").forEach((b) => { b.classList.toggle("on", b.dataset.t === studyTab); b.onclick = () => { studyTab = b.dataset.t; study(); }; });
  const body = $("#sbody");
  if (studyTab === "today") {
    const r = await api("study.today");
    $('.seg [data-t="review"]').textContent = r.reviewCount ? `Review · ${r.reviewCount}` : "Review";
    if (!r.questions.length) { body.innerHTML = `<div class="card empty">No new questions left. Check Review.</div>`; return; }
    body.innerHTML = `<div id="qs" style="display:flex;flex-direction:column;gap:14px"></div>
      <div style="display:flex;gap:10px;margin-top:14px"><button class="btn ghost" id="mr" style="flex:1">One more Regs</button><button class="btn ghost" id="mn" style="flex:1">One more Nav</button></div>`;
    const qs = $("#qs"); const shown = r.questions.map((q) => q.id);
    r.questions.forEach((q) => qs.append(qCard(q)));
    const more = async (subject) => { try { const m = await api("study.more", { subject, exclude: shown }); if (!m.questions.length) return toast("No more new ones in " + SUBJ[subject]); shown.push(m.questions[0].id); const c = qCard(m.questions[0]); qs.append(c); c.scrollIntoView({ behavior: "smooth" }); } catch (e) { fail(e); } };
    $("#mr").onclick = () => more("regs");
    $("#mn").onclick = () => more(Math.random() < 0.5 ? "gnav" : "rnav");
  } else if (studyTab === "review") {
    const r = await api("study.review");
    $('.seg [data-t="review"]').textContent = r.questions.length ? `Review · ${r.questions.length}` : "Review";
    if (!r.questions.length) { body.innerHTML = `<div class="card empty">Nothing to review today. Questions you get wrong come back here after 3 days.</div>`; return; }
    body.innerHTML = `<div id="qs" style="display:flex;flex-direction:column;gap:14px"></div>`;
    r.questions.forEach((q) => $("#qs").append(qCard({ ...q, answeredToday: null })));
  } else {
    const p = await api("study.progress");
    body.innerHTML = `<div class="card" style="display:flex;flex-direction:column;gap:14px">
      ${["regs", "gnav", "rnav"].map((s) => { const x = p.subjects[s]; return `<div><div class="row-between"><span>${SUBJ_LONG[s]}</span><span class="mut">${x.seen} of ${x.total} seen</span></div>
      <div class="track" style="margin-top:6px;height:8px"><span style="width:${x.total ? (x.seen / x.total) * 100 : 0}%"></span></div>
      <div class="mut" style="margin-top:4px">${x.correct} right last time · ${x.toReview} to review</div></div>`; }).join("")}
    </div>
    <div class="card"><div class="lab" style="margin-bottom:8px">Needs work</div>
      ${p.weak.length ? p.weak.map((w) => `<div class="list-item"><span class="tile">${w.wrong}</span><div><div>${esc(w.topic)}</div><div class="mut">${SUBJ_LONG[w.subject]}</div></div></div>`).join("") : `<div class="mut">Topics you miss most will show here.</div>`}
    </div>`;
  }
}
function qCard(q) {
  const el = document.createElement("div");
  el.className = "card";
  const L = "ABCD";
  const render = (res) => {
    const answered = res ?? (q.answeredToday ? { chosen: q.answeredToday.chosen, correct: q.answeredToday.correct, answer: q.answer } : null);
    el.innerHTML = `<div style="display:flex;flex-direction:column;gap:10px">
      <div class="row-between"><span class="lab">${SUBJ[q.subject]}</span><span class="mut" style="text-align:right">${esc(q.topic)}</span></div>
      <div class="qtext">${esc(q.question)}</div>
      ${q.options.map((o, i) => { let cls = ""; if (answered) { if (i === answered.answer) cls = "right"; else if (i === answered.chosen) cls = "wrong"; } return `<button class="opt ${cls}" data-i="${i}" ${answered ? "disabled" : ""}><b>${L[i]}</b><span>${esc(o)}</span></button>`; }).join("")}
      ${answered ? `<div style="border-top:1px dashed var(--stitch);padding-top:10px">
        <div class="lab" style="margin-bottom:6px">${answered.correct ? "Right" : "Not quite"} — why</div>
        <div style="font-size:14px;line-height:1.5">${esc(q.explanation)}</div>
        <div class="mut" style="margin-top:8px">${q.ref ? esc(q.ref) + " · " : ""}${answered.correct ? (res?.dueDay ? "Next review " + niceDate(res.dueDay) : "Learned") : "Comes back in 3 days"}</div></div>` : ""}
    </div>`;
    if (!answered) $$(".opt", el).forEach((b) => (b.onclick = async () => {
      $$(".opt", el).forEach((x) => (x.disabled = true));
      try { const r = await api("study.answer", { qid: q.id, chosen: Number(b.dataset.i) }); render({ chosen: Number(b.dataset.i), ...r }); }
      catch (e) { fail(e); $$(".opt", el).forEach((x) => (x.disabled = false)); }
    }));
  };
  render();
  return el;
}

// ---------- interview ----------
async function interview() {
  app.innerHTML = spinner();
  const r = await api("interview.today");
  let q = r.question;
  const recs = r.recordings;
  app.innerHTML = `<h1>Interview</h1>
  <div class="card" id="qc"></div>
  <div style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:4px 0">
    <div class="timer-ring"><div class="timer-core" style="width:170px;height:170px"><div class="n" id="clock">2:00</div><div class="mut" id="clockSub">2 minutes</div></div></div>
    <button class="rec" id="rec" aria-label="Start recording"><i></i></button>
    <div class="mut" id="recHint">Tap to record. It stops at 2:00.</div>
    <div id="after" style="display:none;width:100%;flex-direction:column;gap:10px"></div>
  </div>
  <div class="card" style="padding-bottom:8px"><div class="lab">Past answers</div>
    <div id="past" style="margin-top:6px">${recs.length ? recs.map((x) => `<div class="list-item" style="flex-wrap:wrap;padding:8px 0">
      <div style="flex:1;min-width:200px"><div style="font-size:14px">${esc(x.question)}</div><div class="mut">${whenLabel(x.at)} · ${mmss(x.seconds)}</div></div>
      <button class="btn small ghost" data-del="${x.id}" aria-label="Delete this answer">Delete</button>
      ${x.url ? `<audio controls preload="none" src="${esc(x.url)}" style="width:100%;height:36px"></audio>` : ""}</div>`).join("") : `<div class="mut" style="padding:8px 0">Your recorded answers will be here to listen back to.</div>`}</div></div>`;
  const drawQ = () => {
    if (!q) { $("#qc").innerHTML = `<div class="empty">Questions are on their way.</div>`; return; }
    $("#qc").innerHTML = `<div style="display:flex;flex-direction:column;gap:10px">
      <div class="seg" role="tablist">${Object.entries(KIND).map(([k, v]) => `<button data-k="${k}" class="${q.kind === k ? "on" : ""}">${v}</button>`).join("")}</div>
      <div class="qtext">${esc(q.question)}</div>
      <details><summary class="mut" style="min-height:32px;cursor:pointer;font-size:13.5px">What a good answer covers</summary>
      <ul style="margin:8px 0 0;padding-left:18px;font-size:14px;line-height:1.5;display:flex;flex-direction:column;gap:4px">${q.covers.split("|").map((c) => `<li>${esc(c.trim())}</li>`).join("")}</ul></details>
      <button class="btn small ghost" id="another" style="align-self:flex-start">Different question</button></div>`;
    $$("#qc .seg button").forEach((b) => (b.onclick = () => another(b.dataset.k)));
    $("#another").onclick = () => another(q.kind);
  };
  const another = async (kind) => { try { const a = await api("interview.another", { kind, not: q?.id }); if (a.question) { q = a.question; drawQ(); } } catch (e) { fail(e); } };
  drawQ();
  $$("[data-del]").forEach((b) => (b.onclick = async () => { if (!confirm("Delete this recording?")) return; try { await api("interview.delete", { id: Number(b.dataset.del) }); interview(); } catch (e) { fail(e); } }));

  // recorder
  let mr = null, chunks = [], t0 = 0, tick = null, stream = null, blob = null, secs = 0;
  const LIMIT = 120;
  const stopAll = () => { clearInterval(tick); if (mr && mr.state !== "inactive") mr.stop(); stream?.getTracks().forEach((t) => t.stop()); };
  cleanup = stopAll;
  $("#rec").onclick = async () => {
    if (mr && mr.state === "recording") { mr.stop(); return; }
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
    catch { return toast("Allow the microphone for Dragonfly to record"); }
    const type = ["audio/mp4", "audio/webm;codecs=opus", "audio/webm"].find((t) => window.MediaRecorder?.isTypeSupported?.(t)) || "";
    mr = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
    chunks = []; $("#after").style.display = "none";
    mr.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    mr.onstop = () => {
      clearInterval(tick); stream.getTracks().forEach((t) => t.stop());
      secs = Math.min(LIMIT, (Date.now() - t0) / 1000);
      blob = new Blob(chunks, { type: mr.mimeType || type || "audio/mp4" });
      $("#rec").classList.remove("on"); $("#rec").setAttribute("aria-label", "Start recording");
      $("#recHint").textContent = `Recorded ${mmss(secs)}`;
      const after = $("#after"); after.style.display = "flex";
      after.innerHTML = `<audio controls src="${URL.createObjectURL(blob)}" style="width:100%"></audio>
        <div style="display:flex;gap:10px"><button class="btn ghost" id="redo" style="flex:1">Redo</button><button class="btn" id="keep" style="flex:2">Save answer</button></div>`;
      $("#redo").onclick = () => { after.style.display = "none"; $("#clock").textContent = "2:00"; $("#recHint").textContent = "Tap to record. It stops at 2:00."; };
      $("#keep").onclick = save;
    };
    mr.start(); t0 = Date.now();
    $("#rec").classList.add("on"); $("#rec").setAttribute("aria-label", "Stop recording");
    $("#recHint").textContent = "Recording… tap to stop";
    tick = setInterval(() => {
      const left = LIMIT - (Date.now() - t0) / 1000;
      $("#clock").textContent = mmss(left);
      $("#clockSub").textContent = left <= 15 ? "wrap up" : "left";
      if (left <= 0) mr.stop();
    }, 250);
  };
  const save = async () => {
    $("#keep").disabled = true; $("#keep").textContent = "Saving…";
    try {
      const ext = /webm/.test(blob.type) ? "webm" : "m4a";
      const u = await api("interview.uploadUrl", { ext });
      const put = await fetch(u.signedUrl, { method: "PUT", headers: { "content-type": blob.type || "audio/mp4", "x-upsert": "false" }, body: blob });
      if (!put.ok) throw new Error("Upload failed — try again");
      await api("interview.save", { qid: q?.id, question: q?.question ?? "Practice", path: u.path, seconds: secs });
      toast("Saved"); interview();
    } catch (e) { fail(e); $("#keep").disabled = false; $("#keep").textContent = "Save answer"; }
  };
}

// ---------- calm ----------
let calmTab = "wimhof";
const PACE = { Slow: 4, Med: 3, Fast: 2 };
function wimSettings() { return { rounds: 3, breaths: 30, pace: "Med", recovery: 15, ...store.get("df.wim", {}) }; }
let audioCtx = null;
function chime(freq = 528, dur = 1.6, vol = 0.18) {
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    const o = audioCtx.createOscillator(), g = audioCtx.createGain();
    o.type = "sine"; o.frequency.value = freq; o.connect(g); g.connect(audioCtx.destination);
    const t = audioCtx.currentTime; g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(vol, t + 0.03); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.start(t); o.stop(t + dur);
  } catch {}
}
async function wake() { try { return await navigator.wakeLock?.request("screen"); } catch { return null; } }
async function calm() {
  app.innerHTML = spinner();
  const recent = await api("calm.recent").catch(() => ({ sessions: [] }));
  const lastWim = recent.sessions.find((s) => s.kind === "wimhof" && s.holds.length);
  app.innerHTML = `<h1>Calm</h1>
  <div class="seg"><button data-t="wimhof">Wim Hof</button><button data-t="timer">Timer</button></div>
  <div id="cbody"></div>`;
  $$(".seg button").forEach((b) => { b.classList.toggle("on", b.dataset.t === calmTab); b.onclick = () => { if (cleanup) { cleanup(); cleanup = null; } calmTab = b.dataset.t; calm(); }; });
  (calmTab === "wimhof" ? wimhof : timer)($("#cbody"), lastWim);
}
function wimhof(el, lastWim) {
  const S = wimSettings();
  el.innerHTML = `
  <div style="display:flex;flex-direction:column;align-items:center;gap:10px;padding:8px 0">
    <div class="lab" id="phase">Ready</div>
    <div class="timer-ring"><button class="timer-core" id="core" aria-label="Breathing circle"><div class="n" id="big">${S.breaths}</div><div class="mut" id="small">breaths a round</div></button></div>
    <div class="serif" style="font-size:22px;font-weight:600;text-align:center;min-height:28px" id="cue">Sit or lie down somewhere safe</div>
    <div class="mut" style="text-align:center;max-width:290px" id="hint">During the hold, tap the circle the moment you need to breathe.</div>
  </div>
  <div class="setgrid" id="sets">
    <button data-s="rounds"><b>${S.rounds}</b><span class="mut">rounds</span></button>
    <button data-s="breaths"><b>${S.breaths}</b><span class="mut">breaths</span></button>
    <button data-s="pace"><b>${S.pace}</b><span class="mut">pace</span></button>
    <button data-s="recovery"><b>${S.recovery}s</b><span class="mut">recovery</span></button>
  </div>
  <div class="row-between"><div><div style="font-size:14px">Last holds</div><div class="serif" style="font-size:20px;color:var(--gold-soft)">${lastWim ? lastWim.holds.map(mmss).join(" · ") : "—"}</div></div>
  <button class="btn" id="go" style="min-height:50px;padding:0 30px">Start</button></div>`;
  const CYC = { rounds: [1, 2, 3, 4, 5], breaths: [20, 30, 40], pace: ["Slow", "Med", "Fast"], recovery: [10, 15, 20, 30] };
  $$("#sets button", el).forEach((b) => (b.onclick = () => { if (running) return; const k = b.dataset.s, list = CYC[k]; S[k] = list[(list.indexOf(S[k]) + 1) % list.length]; store.set("df.wim", S); wimhof(el, lastWim); }));
  let running = false, timerId = null, lock = null, holds = [], phase = "idle", round = 0, holdStart = 0, sessionStart = 0;
  const core = $("#core", el), big = $("#big", el), small = $("#small", el), cue = $("#cue", el), ph = $("#phase", el);
  const stop = (saveIt) => {
    running = false; clearTimeout(timerId); clearInterval(timerId); lock?.release?.(); core.style.transform = "";
    if (saveIt && holds.length) api("calm.save", { kind: "wimhof", seconds: (Date.now() - sessionStart) / 1000, holds }).then(() => toast("Saved — nice work")).catch(fail);
  };
  cleanup = () => stop(true);
  const breathe = () => {
    phase = "breathe"; round++; let n = 0; const per = PACE[S.pace] * 1000;
    ph.textContent = `Round ${round} of ${S.rounds}`; cue.textContent = "Breathe in fully, let go"; small.textContent = `of ${S.breaths} breaths`;
    const one = () => {
      if (!running) return;
      n++; big.textContent = n;
      core.style.transitionDuration = per / 2000 + "s"; core.style.transform = "scale(1.12)";
      timerId = setTimeout(() => { core.style.transform = "scale(0.9)"; timerId = setTimeout(() => (n < S.breaths ? one() : hold()), per / 2); }, per / 2);
    };
    one();
  };
  const hold = () => {
    phase = "hold"; chime(396); core.style.transform = "scale(0.95)";
    cue.textContent = "Breathe out and hold"; small.textContent = "tap when you need to breathe";
    holdStart = Date.now();
    timerId = setInterval(() => (big.textContent = mmss((Date.now() - holdStart) / 1000)), 250);
  };
  const recover = () => {
    clearInterval(timerId); holds.push(Math.round((Date.now() - holdStart) / 1000));
    phase = "recover"; chime(528); core.style.transitionDuration = "1.5s"; core.style.transform = "scale(1.12)";
    cue.textContent = "Big breath in and hold"; small.textContent = "recovery";
    const end = Date.now() + S.recovery * 1000;
    timerId = setInterval(() => {
      const left = (end - Date.now()) / 1000; big.textContent = Math.ceil(left);
      if (left <= 0) { clearInterval(timerId); chime(660, 1); if (round < S.rounds) breathe(); else finish(); }
    }, 200);
  };
  const finish = () => {
    stop(true); phase = "idle"; ph.textContent = "Done";
    big.textContent = holds.length; small.textContent = holds.length === 1 ? "round" : "rounds"; cue.textContent = "Holds: " + holds.map(mmss).join(" · ");
    $("#go", el).textContent = "Again";
  };
  core.onclick = () => { if (phase === "hold") recover(); };
  $("#go", el).onclick = async () => {
    if (running) { stop(true); wimhof(el, lastWim); return; }
    chime(528, 0.6, 0.05); lock = await wake();
    running = true; holds = []; round = 0; sessionStart = Date.now();
    $("#go", el).textContent = "End"; breathe();
  };
}
function timer(el) {
  let mins = store.get("df.timer", 10);
  el.innerHTML = `
  <div style="display:flex;flex-direction:column;align-items:center;gap:14px;padding:8px 0">
    <div class="timer-ring"><div class="timer-core"><div class="n" id="tbig">${mins}:00</div><div class="mut" id="tsub">minutes</div></div></div>
    <div class="seg" style="width:100%" id="mins">${[5, 10, 15, 20].map((m) => `<button data-m="${m}" class="${m === mins ? "on" : ""}">${m} min</button>`).join("")}</div>
    <div class="mut">A soft bell at the end. Keep the app open.</div>
    <button class="btn wide" id="tgo">Start</button>
  </div>`;
  let id = null, lock = null, start = 0;
  const stop = (saveSecs) => { clearInterval(id); id = null; lock?.release?.(); if (saveSecs > 30) api("calm.save", { kind: "timer", seconds: saveSecs }).then(() => toast("Saved")).catch(fail); };
  cleanup = () => id && stop((Date.now() - start) / 1000);
  $$("#mins button", el).forEach((b) => (b.onclick = () => { if (id) return; mins = Number(b.dataset.m); store.set("df.timer", mins); timer(el); }));
  $("#tgo", el).onclick = async () => {
    if (id) { stop((Date.now() - start) / 1000); timer(el); return; }
    chime(528, 2, 0.12); lock = await wake(); start = Date.now();
    $("#tgo", el).textContent = "End"; $("#tsub", el).textContent = "left";
    id = setInterval(() => {
      const left = mins * 60 - (Date.now() - start) / 1000;
      $("#tbig", el).textContent = mmss(left);
      if (left <= 0) { stop(mins * 60); chime(528, 3); setTimeout(() => chime(528, 3), 1800); setTimeout(() => chime(660, 4), 3600); $("#tsub", el).textContent = "done"; $("#tgo", el).textContent = "Again"; }
    }, 250);
  };
}

// ---------- exams ----------
const PAPERS = ["Air Regulations", "Air Navigation", "Meteorology", "Technical General", "Technical Specific", "RTR", "Other"];
async function exams() {
  app.innerHTML = spinner();
  const r = await api("exams.list");
  const today = localISO();
  const up = r.exams.filter((e) => e.exam_date >= today), past = r.exams.filter((e) => e.exam_date < today);
  app.innerHTML = `<a class="iconbtn" href="#/today" aria-label="Back to Today">${BACK}</a>
  <h1>Exams</h1>
  ${up.map((e) => { const n = daysUntil(e.exam_date); return `<div class="card" style="display:flex;align-items:center;gap:14px">
    <div style="text-align:center;width:78px;flex:none"><div class="big" style="font-size:46px">${n}</div><div class="mut">${n === 1 ? "day" : "days"}</div></div>
    <div style="flex:1;border-left:1px dashed var(--stitch);padding-left:14px"><div style="font-weight:600">${esc(e.paper)}</div><div class="mut">${niceDate(e.exam_date)}</div></div>
    <button class="iconbtn" data-del="${e.id}" aria-label="Remove ${esc(e.paper)}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>`; }).join("") || `<div class="mut">No exams yet. Add one below and it'll count down on Today.</div>`}
  <form class="card" id="ef" style="display:flex;flex-direction:column;gap:12px">
    <div class="lab">Add an exam</div>
    <div class="field"><label for="ep">Paper</label><select id="ep" class="input">${PAPERS.map((p) => `<option>${p}</option>`).join("")}</select></div>
    <div class="field" id="eoW" style="display:none"><label for="eo">Name</label><input id="eo" class="input" placeholder="e.g. Class 1 medical"></div>
    <div class="field"><label for="ed">Date</label><input id="ed" class="input" type="date" min="${today}"></div>
    <button class="btn wide">Add countdown</button>
  </form>
  ${past.length ? `<div class="card"><div class="lab" style="margin-bottom:6px">Done</div>${past.map((e) => `<div class="list-item"><div style="flex:1">${esc(e.paper)}<div class="mut">${niceDate(e.exam_date)}</div></div><button class="btn small ghost" data-del="${e.id}">Remove</button></div>`).join("")}</div>` : ""}`;
  $("#ep").onchange = () => ($("#eoW").style.display = $("#ep").value === "Other" ? "flex" : "none");
  $("#ef").onsubmit = async (ev) => {
    ev.preventDefault();
    const paper = $("#ep").value === "Other" ? $("#eo").value.trim() : $("#ep").value, date = $("#ed").value;
    if (!paper || !date) return toast("Pick the paper and the date");
    try { await api("exams.add", { paper, date }); toast("Added"); exams(); } catch (e) { fail(e); }
  };
  $$("[data-del]").forEach((b) => (b.onclick = async () => { if (!confirm("Remove this exam?")) return; try { await api("exams.delete", { id: Number(b.dataset.del) }); exams(); } catch (e) { fail(e); } }));
}

// ---------- settings ----------
async function settings() {
  app.innerHTML = `<a class="iconbtn" href="#/today" aria-label="Back to Today">${BACK}</a>
  <h1>Settings</h1>
  <div class="card" style="display:flex;flex-direction:column;gap:12px">
    <div class="lab">Double back-tap to log a spend</div>
    <ol class="steps">
      <li>Open the <b>Shortcuts</b> app → <b>+</b>. Name it <b>Spent</b>.</li>
      <li>Add <b>Ask for Input</b>. Prompt: <code>Spent?</code></li>
      <li>Add <b>Get Contents of URL</b>. URL: <button class="btn small ghost" data-copy="${API}">Copy URL</button><br>Tap the arrow, set Method <b>POST</b>.<br>Headers: add <code>x-dragonfly-key</code> = <button class="btn small ghost" data-copy="key">Copy code</button><br>Request Body <b>JSON</b>: <code>action</code> = <code>spend</code>, and <code>text</code> = the <b>Provided Input</b> variable.</li>
      <li>Add <b>Get Dictionary Value</b>: Value for <code>message</code>.</li>
      <li>Add <b>Show Notification</b> with the Dictionary Value.</li>
      <li>Settings → Accessibility → Touch → <b>Back Tap</b> → Double Tap → <b>Spent</b>.</li>
    </ol>
    <button class="btn ghost" id="test">Test: log ₹1 "test"</button>
  </div>
  <div class="card"><div class="row-between"><div class="lab">Words it has learned</div><span class="mut" id="lc"></span></div><div id="learned" style="margin-top:6px">${spinner()}</div></div>
  <div class="card" style="display:flex;flex-direction:column;gap:10px">
    <a class="btn ghost" href="#/exams">Exams</a>
    <button class="btn ghost" id="exp">Export everything (file)</button>
    <button class="btn ghost" id="lock">Lock this phone</button>
  </div>
  <div class="mut" style="text-align:center">Dragonfly · made by Dharmik</div>`;
  $$("[data-copy]").forEach((b) => (b.onclick = async () => { const v = b.dataset.copy === "key" ? KEY : b.dataset.copy; try { await navigator.clipboard.writeText(v); toast("Copied"); } catch { prompt("Copy this:", v); } }));
  $("#test").onclick = async () => { try { const r = await api("spend", { text: "1 test", source: "app" }); toast(r.message + " — delete it in Money"); } catch (e) { fail(e); } };
  $("#lock").onclick = () => { if (!confirm("Lock Dragonfly on this phone? You'll need the setup code again.")) return; store.del("df.key"); KEY = null; location.hash = "#/setup"; };
  $("#exp").onclick = async () => {
    try {
      const data = await api("export");
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const a = document.createElement("a"); a.href = url; a.download = `dragonfly-${localISO()}.json`; document.body.append(a); a.click(); a.remove();
    } catch (e) { fail(e); }
  };
  const drawLearned = async () => {
    const r = await api("learned.list");
    $("#lc").textContent = r.words.length || "";
    $("#learned").innerHTML = r.words.length ? r.words.map((w) => `<div class="list-item"><div style="flex:1">${esc(w.word)} → ${esc(w.category)}</div><button class="btn small ghost" data-w="${esc(w.word)}">Forget</button></div>`).join("") : `<div class="mut">When you fix a category with "Remember" on, the word shows here.</div>`;
    $$("#learned [data-w]").forEach((b) => (b.onclick = async () => { try { await api("learned.delete", { word: b.dataset.w }); drawLearned(); } catch (e) { fail(e); } }));
  };
  drawLearned().catch(fail);
}

route();
