# Digest

`latest.json` is what the Sthira app shows on its Digest tab. A scheduled task rewrites it once a day,
a little before 13:00 India time. The app fetches it from
`https://raw.githubusercontent.com/dev-d-14/drill/main/digest/latest.json`.

The file holds exactly ten items, best first. The phone shows all of them, reordered by the taste it has
learned from the more/less buttons, so the votes never leave the phone. That order is settled when the digest is
first opened and then held for the day, so voting never shifts the list under your thumb; the taste it teaches
lands on the next digest.

## Format

```json
{
  "date": "2026-11-17",
  "items": [
    {
      "id": "2026-11-17-1",
      "tag": "AI",
      "kind": "news",
      "title": "One plain line, written in our own words, under 90 characters",
      "why": "One line on why it matters to the reader, under 140 characters",
      "url": "https://example.com/the-primary-source",
      "source": "Name of the source"
    },
    {
      "id": "2026-11-17-2",
      "tag": "Science",
      "kind": "science",
      "evidence": "strong",
      "title": "The finding, in plain words",
      "why": "What to change in training or study because of it",
      "url": "https://example.com/the-study",
      "source": "Journal or lab"
    }
  ],
  "drill": {
    "q": "Which claim in item 2 is the weakest?",
    "options": ["First claim", "Second claim", "Third claim"],
    "answer": 1,
    "why": "One or two sentences explaining the weak point."
  }
}
```

- `date` is the day in India (Asia/Kolkata), as `YYYY-MM-DD`. The app treats the digest as today's only when it matches.
- `tag` is one of `AI`, `Tools`, `Industry`, `India`, `Research`, `Science`, `ISB`. The phone learns a weight per tag. `ISB` is the one daily item meant to build business/economy literacy for Dharmik's ISB interview prep - it's the one slot where corporate strategy, funding, and leadership stories are the point, not the exception.
- `kind` is `news` or `science`. Science items also carry `evidence`: `strong`, `moderate` or `early`.
- `drill` is optional and appears on Sundays only. `answer` is the index of the correct option.
- Titles and `why` lines are always written fresh. Nothing is copied from the source.
