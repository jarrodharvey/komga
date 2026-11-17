# OCR for reader auto-advance

The web reader's auto-advance (`a` to toggle, Space to pause) waits longer on pages and panels with more text:
`time = baseline + words × (60 s / reading speed)`, clamped to 1.5–60 s. The word counts come from OCR done
ahead of time by these scripts. Books without OCR still auto-advance, using the baseline time only.

```sh
personal-scripts/ocr/ocr-library.py              # OCR every comic book not done yet (server must be running)
personal-scripts/ocr/ocr-library.py --dry-run    # list what would be done
personal-scripts/ocr/ocr-library.py --book ID    # or --series ID / --library ID; --force to redo
```

- Uses Apple Vision through a small Swift helper (`ocr-page.swift`, compiled on first run). About 0.6 s per
  page on an M3: the page is read whole and in overlapping half-page bands, because Vision misses small lettering
  on a full page.
- Writes `config-dir/ocr/<bookId>.json` with every word's box. The server serves it at
  `GET /api/v1/books/{id}/ocr`, and the reader counts the words falling in each panel.
- A book is redone automatically when its file changes (size or hash), so re-running is cheap.
- Auth: `KOMGA_API_KEY`, or `KOMGA_USER`/`KOMGA_PASSWORD` (defaults to the dev admin user). `KOMGA_URL` or
  `--url` for another server.
- Sound effects and huge lettering (taller than 5% of the page) and low-confidence words are ignored by the reader.
