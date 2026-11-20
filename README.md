# Drill

Flashcard app (ML, system design, DSA, CS core, HR) in a thermal-camera look.
Tap the card to flip it (3D flip), then mark **Again** or **Got it** (Leitner boxes: hotter = needs more work).
Switch decks with the chips on top. The CARDS tab shows progress, lets you add cards and import a JSON file.

Every push to `main` builds the APKs on GitHub Actions and publishes them to the **latest** release.

# Sthira

A second app in this repo (`sthira/`), for the day rather than for study: calendar, habits and training in one place.

- **Today:** your phone calendar's events and the day's habits in order, with the next one highlighted.
- **Calendar:** month view over the phone's own calendar. Add, edit and delete events; Google Calendar keeps syncing underneath.
- **Train:** today's workout from an editable plan. Tap a box to log a set; a rest timer runs after each one. Steps on top: Health Connect totals (Google Fit and others), or the phone's own step counter, against a 10,000 goal with a 7-day chart.
- **Plans:** switch, copy and edit plans, or paste one in as text.
- **Digest:** five items a day from `digest/latest.json`, ranked on the phone by the more/less buttons.
- **Meditate, Shutdown, Review:** a one-tap timer with a bell (or guided Wim Hof breathing: set rounds, breaths, pace and recovery; tap during the hold when you need air), a night check-in, and a week that adds itself up.
- **Sleep:** tap Start at bedtime; the microphone listens for movement and breathing (no audio is stored) and a smart alarm wakes you in light sleep inside a 10–45 minute window, never later than the set time. Morning report with estimated stages, score and history; nights go to Health Connect.
- **Calendar extras:** type "gym tomorrow 7pm" to add an event (with a preview first); countdown chips for exams and deadlines in the next two weeks; the next Codeforces contests (tap to add one); an ISB interview question each day with a 2-minute answer timer.
- **Backup:** Review → Backup to Google Drive saves one file in Drive through Android's file picker and overwrites it weekly; Restore brings it back.
- **Quick Settings tiles:** Sleep, Meditate and Wim Hof.
- **Widget:** a large home-screen stamp showing today, in the same design as the Ashtavakra widget.

Plain Java with no libraries, styled after the Ashtavakra app: deep maroon, cream ink, a halftone sun.
