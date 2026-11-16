# Drill

Flashcard app (ML, system design, DSA, CS core, HR) in a thermal-camera look.
Tap the card to flip it (3D flip), then mark **Again** or **Got it** (Leitner boxes: hotter = needs more work).
Switch decks with the chips on top. The CARDS tab shows progress, lets you add cards and import a JSON file.

Every push to `main` builds the APKs on GitHub Actions and publishes them to the **latest** release.

# Sthira

A second app in this repo (`sthira/`), for the day rather than for study: calendar, habits and training in one place.

- **Today:** your phone calendar's events and the day's habits in order, with the next one highlighted.
- **Calendar:** month view over the phone's own calendar. Add, edit and delete events; Google Calendar keeps syncing underneath.
- **Train:** today's workout from an editable plan. Tap a box to log a set; a rest timer runs after each one.
- **Plans:** switch, copy and edit plans, or paste one in as text.
- **Digest:** five items a day from `digest/latest.json`, ranked on the phone by the more/less buttons.
- **Meditate, Shutdown, Review:** a one-tap timer with a bell, a night check-in, and a week that adds itself up.
- **Widget:** a large home-screen stamp showing today, in the same design as the Ashtavakra widget.

Plain Java with no libraries. Bebas Neue is bundled under the SIL Open Font License (`sthira/src/main/assets/fonts/OFL.txt`).
