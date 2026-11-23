# Dragonfly

Hasini's daily app, added to her iPhone home screen as a web app. Deep green, gold thread and bronze, after her dragonfly embroidery.

- **Today:** exam countdowns, what she's spent this month, and a five-item flight plan: an Air Regs question, a Nav question (General and Radio Nav on alternate days), a 2-minute interview answer, a breathing session and knee exercises (done or not, with the week as dots).
- **Money:** no budget, just what she spends. Double-tap the back of the phone, type "250 chai", and it's saved and sorted into a category. Fix a category once with "Remember" on and it learns the word. Month total, daily average, last month at the same point, last 7 days and categories.
- **Study:** questions written from her books (Bali's Air Regulations, Oxford General and Radio Navigation) with the book page or chapter. Wrong answers come back after 3 days, then 7 and 16 until learned.
- **Interview:** a first-officer interview question a day (scenario, technical, airline and HR) with what a good answer covers, a 2-minute recorder and past answers to play back.
- **Calm:** Wim Hof breathing (rounds, breaths, pace and recovery; tap during the hold when she needs air) and a quiet timer with a bell.
- **Exams:** add papers and dates; the nearest ones count down on Today.

## How it fits together

- `web/` is the app: plain HTML, CSS and JS, no build step, hosted on Vercel (root directory `dragonfly/web`).
- `supabase/functions/api` is the one endpoint everything talks to. Every request carries the setup code in `x-dragonfly-key`; the database stores only its SHA-256. Tables have row level security on with no policies, so nothing is reachable with the public key.
- `supabase/functions/seed` loads `content/seed.json` (built by `content/build_sql.py` from `questions.py` and `interview.py`). To add questions: edit those files, run `python3 build_sql.py`, push, then POST to the seed function with the code.
- The back-tap shortcut posts `{"action":"spend","text":…}` to the api function and shows the `message` it returns.

## Hosting

Live at https://dragonfly-hasini.vercel.app (Vercel project `dragonfly`). Vercel isn't linked to this repo (the GitHub integration isn't installed), so the hosted `index.html` is `deploy-index.html` with `__SHA__` replaced by a commit id: the CSS and JS load from jsDelivr pinned to that commit. To ship a change: push, then redeploy `deploy-index.html` with the new commit id. Linking the repo in Vercel (root directory `dragonfly/web`) would make every push deploy on its own.
