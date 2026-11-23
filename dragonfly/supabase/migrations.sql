-- Schema applied to the Supabase project "dragonfly" (ref focgmnxwdosljubcbsgw).
-- All tables have RLS on with no policies: only the edge functions (service role) touch them.
create table app_keys (id int primary key default 1, key_hash text not null, created_at timestamptz default now());
create table expenses (id bigint generated always as identity primary key, amount numeric(12,2) not null check (amount > 0), note text not null default '', category text, spent_at timestamptz not null default now(), source text not null default 'app');
create table learned_words (word text primary key, category text not null, created_at timestamptz default now());
create table questions (id text primary key, subject text not null check (subject in ('regs','gnav','rnav','met')), topic text not null, question text not null, options jsonb not null, answer int not null, explanation text not null, ref text, ord int not null default 0);
create table attempts (id bigint generated always as identity primary key, qid text not null references questions(id) on delete cascade, chosen int not null, correct boolean not null, at timestamptz not null default now());
create table qstate (qid text primary key references questions(id) on delete cascade, box int not null default 0, due_at timestamptz, last_correct boolean, seen_at timestamptz default now());
create table daily_pick (day date not null, slot text not null, qid text not null references questions(id) on delete cascade, primary key (day, slot));
create table interview_questions (id text primary key, kind text not null check (kind in ('scenario','technical','airline')), question text not null, covers text not null, ord int not null default 0);
create table recordings (id bigint generated always as identity primary key, qid text references interview_questions(id) on delete set null, question text not null, path text not null, seconds int not null default 0, at timestamptz not null default now());
create table calm_sessions (id bigint generated always as identity primary key, kind text not null check (kind in ('wimhof','timer')), seconds int not null default 0, holds jsonb not null default '[]', at timestamptz not null default now());
create table knee_days (day date primary key);
create table exams (id bigint generated always as identity primary key, paper text not null, exam_date date not null, created_at timestamptz default now());
-- + enable row level security on each, and a private storage bucket "recordings".
create table categories (name text primary key, ord int not null default 100, created_at timestamptz default now()); -- seeded with the 9 defaults
