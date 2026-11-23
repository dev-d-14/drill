import re, json, glob, sys
sys.path.insert(0, '.')
from questions import REGS, GNAV, RNAV
from interview import INTERVIEW
pages = {}
for f in sorted(glob.glob('/home/claude/ocr/p*.txt')):
    n = int(re.findall(r'p(\d+)', f)[0]); pages[n] = re.sub(r'\s+', ' ', open(f, errors='ignore').read())
def page_for(rx):
    for n in range(18, 741):
        if re.search(rx, pages.get(n, '')): return n - 15
    return None
def q(s): return "'" + s.replace("'", "''") + "'"
rows = []; miss = []
for i, (id_, topic, qu, opts, ans, expl, find) in enumerate(REGS):
    assert len(opts) == 4 and 0 <= ans < 4, id_
    p = page_for(find); 
    if p is None: miss.append(id_)
    ref = f"Air Regulations (Bali), p. {p}" if p else "Air Regulations (Bali)"
    rows.append((id_, 'regs', topic, qu, opts, ans, expl, ref, i))
for subj, bank in (('gnav', GNAV), ('rnav', RNAV)):
    for i, (id_, topic, qu, opts, ans, expl, ref) in enumerate(bank):
        assert len(opts) == 4 and 0 <= ans < 4, id_
        rows.append((id_, subj, topic, qu, opts, ans, expl, ref, i))
ids = [r[0] for r in rows]; assert len(ids) == len(set(ids))
# spread answers: shuffle option order deterministically so the right answer isn't always A/B
import random
out = []
for r in rows:
    id_, subj, topic, qu, opts, ans, expl, ref, i = r
    rnd = random.Random(id_); order = list(range(4)); rnd.shuffle(order)
    nopts = [opts[k] for k in order]; nans = order.index(ans)
    out.append(f"({q(id_)},{q(subj)},{q(topic)},{q(qu)},{q(json.dumps(nopts, ensure_ascii=False))}::jsonb,{nans},{q(expl)},{q(ref)},{i})")
sql = "insert into questions (id,subject,topic,question,options,answer,explanation,ref,ord) values\n" + ",\n".join(out) + "\non conflict (id) do update set subject=excluded.subject, topic=excluded.topic, question=excluded.question, options=excluded.options, answer=excluded.answer, explanation=excluded.explanation, ref=excluded.ref, ord=excluded.ord;\n"
iv = ",\n".join(f"({q(a)},{q(b)},{q(c)},{q(d)},{k})" for k, (a, b, c, d) in enumerate(INTERVIEW))
sql += "insert into interview_questions (id,kind,question,covers,ord) values\n" + iv + "\non conflict (id) do update set kind=excluded.kind, question=excluded.question, covers=excluded.covers, ord=excluded.ord;\n"
open('seed.sql', 'w').write(sql)
from collections import Counter
print('rows', len(rows), 'interview', len(INTERVIEW), 'missing pages', miss)
print(Counter(r[1] for r in rows))
# JSON seed for the seed edge function
qs = []
for r in rows:
    id_, subj, topic, qu, opts, ans, expl, ref, i = r
    rnd = random.Random(id_); order = list(range(4)); rnd.shuffle(order)
    qs.append(dict(id=id_, subject=subj, topic=topic, question=qu, options=[opts[k] for k in order], answer=order.index(ans), explanation=expl, ref=ref, ord=i))
iq = [dict(id=a, kind=b, question=c, covers=d, ord=k) for k, (a, b, c, d) in enumerate(INTERVIEW)]
json.dump(dict(questions=qs, interview=iq), open('seed.json', 'w'), ensure_ascii=False, indent=1)
