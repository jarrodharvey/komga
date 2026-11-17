#!/usr/bin/env python3
"""
OCR Komga books with Apple Vision so the web reader's auto-advance can give text-heavy pages and
panels more time. For each book it writes <out>/<bookId>.json with every page's word boxes
(relative coordinates, top-left origin). The Komga backend serves these at
GET /api/v1/books/{bookId}/ocr.

Books whose OCR file is already current (same file size, hash and page count) are skipped, so
the script can be re-run at any time to pick up new books.

Usage (run from anywhere, the server must be running):
  personal-scripts/ocr/ocr-library.py                 # every book
  personal-scripts/ocr/ocr-library.py --book ID ...    # specific books
  personal-scripts/ocr/ocr-library.py --series ID ...  # every book in some series
  personal-scripts/ocr/ocr-library.py --library ID     # every book in a library
Auth: KOMGA_API_KEY, or KOMGA_USER / KOMGA_PASSWORD (default: the dev admin user).
"""
import argparse
import base64
import concurrent.futures
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
OCR_BIN = SCRIPT_DIR / "ocr-page"
OCR_SRC = SCRIPT_DIR / "ocr-page.swift"
ENGINE = "apple-vision-tiled-1"
# Formats Apple's ImageIO decodes; anything else is converted to PNG by the server.
NATIVE_TYPES = {"image/jpeg", "image/png", "image/webp", "image/gif", "image/heif", "image/heic", "image/avif",
                "image/tiff", "image/bmp", "image/jxl"}


class Komga:
    def __init__(self, url):
        self.url = url.rstrip("/")
        self.headers = {}
        if key := os.environ.get("KOMGA_API_KEY"):
            self.headers["X-API-Key"] = key
        else:
            user = os.environ.get("KOMGA_USER", "admin@example.org")
            password = os.environ.get("KOMGA_PASSWORD", "admin")
            token = base64.b64encode(f"{user}:{password}".encode()).decode()
            self.headers["Authorization"] = f"Basic {token}"

    def get(self, path, params=None, accept="application/json"):
        url = f"{self.url}{path}"
        if params:
            url += "?" + urllib.parse.urlencode(params, doseq=True)
        req = urllib.request.Request(url, headers={**self.headers, "Accept": accept})
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.read()

    def json(self, path, params=None):
        return json.loads(self.get(path, params))


def ensure_binary():
    if not OCR_BIN.exists() or OCR_BIN.stat().st_mtime < OCR_SRC.stat().st_mtime:
        print("Compiling the OCR helper...", file=sys.stderr)
        subprocess.run(["swiftc", "-O", str(OCR_SRC), "-o", str(OCR_BIN)], check=True)


def list_books(komga, args):
    if args.book:
        return [komga.json(f"/api/v1/books/{b}") for b in args.book]
    if args.series:
        return [b for s in args.series
                for b in komga.json(f"/api/v1/series/{s}/books", {"unpaged": "true"})["content"]]
    params = {"unpaged": "true", "media_status": "READY", "sort": "metadata.numberSort,asc"}
    if args.library:
        params["library_id"] = args.library
    return komga.json("/api/v1/books", params)["content"]


def is_current(out_file, book):
    try:
        existing = json.loads(out_file.read_text())
    except (OSError, ValueError):
        return False
    return (existing.get("engine") == ENGINE
            and existing.get("fileSize") == book["sizeBytes"]
            and existing.get("fileHash") == book["fileHash"]
            and existing.get("pageCount") == book["media"]["pagesCount"])


def has_letter(text):
    return any(c.isalpha() for c in text)


def ocr_book(komga, book, out_file):
    book_id = book["id"]
    pages = komga.json(f"/api/v1/books/{book_id}/pages")
    with tempfile.TemporaryDirectory(prefix="komga-ocr-") as tmp:
        def download(page):
            n = page["number"]
            params = None if page.get("mediaType") in NATIVE_TYPES else {"convert": "png"}
            path = Path(tmp) / f"{n:05d}"
            path.write_bytes(komga.get(f"/api/v1/books/{book_id}/pages/{n}", params, accept="image/*"))
            return n, str(path)

        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            files = dict(pool.map(download, pages))

        by_path = {p: n for n, p in files.items()}
        proc = subprocess.run([str(OCR_BIN), *files.values()], check=True, capture_output=True, text=True)
        results = {}
        for line in proc.stdout.splitlines():
            r = json.loads(line)
            if r.get("error"):
                print(f"    page {by_path[r['path']]}: {r['error']}", file=sys.stderr)
            results[by_path[r["path"]]] = [
                {"x": round(w["x"], 4), "y": round(w["y"], 4), "w": round(w["w"], 4), "h": round(w["h"], 4),
                 "conf": round(w["conf"], 2), "text": w["text"]}
                for w in r["words"] if has_letter(w["text"])
            ]

    data = {
        "bookId": book_id,
        "engine": ENGINE,
        "fileSize": book["sizeBytes"],
        "fileHash": book["fileHash"],
        "pageCount": book["media"]["pagesCount"],
        "pages": [{"number": n, "words": results.get(n, [])} for n in sorted(files)],
    }
    out_file.parent.mkdir(parents=True, exist_ok=True)
    tmp_file = out_file.with_suffix(".json.tmp")
    tmp_file.write_text(json.dumps(data, separators=(",", ":"), ensure_ascii=False))
    tmp_file.replace(out_file)
    return sum(len(p["words"]) for p in data["pages"])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=os.environ.get("KOMGA_URL", "http://localhost:8080"))
    parser.add_argument("--out", type=Path, default=REPO_ROOT / "config-dir" / "ocr",
                        help="Directory for the OCR files; must be <komga config-dir>/ocr (default: %(default)s)")
    parser.add_argument("--book", nargs="+", help="Book IDs")
    parser.add_argument("--series", nargs="+", help="Series IDs")
    parser.add_argument("--library", help="Library ID")
    parser.add_argument("--force", action="store_true", help="Re-OCR books that are already current")
    parser.add_argument("--dry-run", action="store_true", help="Only list the books that would be processed")
    args = parser.parse_args()

    komga = Komga(args.url)
    try:
        books = list_books(komga, args)
    except urllib.error.URLError as e:
        sys.exit(f"Cannot reach Komga at {args.url}: {e}")
    books = [b for b in books if b["media"]["mediaProfile"] in ("DIVINA", "PDF") and b["media"]["status"] == "READY"]
    todo = [b for b in books if args.force or not is_current(args.out / f"{b['id']}.json", b)]
    print(f"{len(books)} comic books, {len(books) - len(todo)} already done, {len(todo)} to OCR")
    if args.dry_run:
        for b in todo:
            print(f"  {b['id']}  {b['name']} ({b['media']['pagesCount']} pages)")
        return
    if todo:
        ensure_binary()

    failed = 0
    for i, book in enumerate(todo, 1):
        start = time.monotonic()
        prefix = f"[{i}/{len(todo)}] {book['name']} ({book['media']['pagesCount']} pages)"
        print(prefix, end="", flush=True)
        try:
            words = ocr_book(komga, book, args.out / f"{book['id']}.json")
            print(f": {words} words in {time.monotonic() - start:.0f}s")
        except KeyboardInterrupt:
            print(" interrupted")
            sys.exit(130)
        except Exception as e:  # keep going with the other books
            failed += 1
            print(f": FAILED ({e})")
    if failed:
        sys.exit(f"{failed} book(s) failed")


if __name__ == "__main__":
    main()
