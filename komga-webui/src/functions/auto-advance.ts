import {BookOcrDto, OcrWordDto, PanelRectDto} from '@/types/komga-books'

// What the reader is currently showing, emitted by the paged and guided readers as a 'step' event.
export interface AutoAdvanceStep {
  // changes whenever the reader shows something else
  key: string,
  // page numbers on screen: one page, or two for a double page spread
  pages: number[],
  // in guided view: all the panels of the page, in reading order, and the index of the zoomed one.
  // Undefined when whole pages are shown.
  panels?: PanelRectDto[],
  panelIndex?: number,
}

export interface AutoAdvanceSettings {
  enabled: boolean,
  // time given to every page (or spread), and to every panel in guided view, before counting words
  pageBaselineMs: number,
  panelBaselineMs: number,
  // reading speed, in words per minute
  wordsPerMinute: number,
}

export const AUTO_ADVANCE_DEFAULTS: AutoAdvanceSettings = {
  enabled: false,
  pageBaselineMs: 5000,
  panelBaselineMs: 2500,
  wordsPerMinute: 240,
}

export const AUTO_ADVANCE_MIN_MS = 1500
export const AUTO_ADVANCE_MAX_MS = 60000

// OCR results below this confidence are mostly noise picked up in the artwork
const MIN_CONFIDENCE = 0.3
// words taller than this fraction of the page are sound effects or titles, not text to read
const MAX_WORD_HEIGHT = 0.05

export function isReadableWord(w: OcrWordDto): boolean {
  return w.conf >= MIN_CONFIDENCE && w.h <= MAX_WORD_HEIGHT
}

function wordsOnPage(ocr: BookOcrDto | null | undefined, page: number): OcrWordDto[] {
  return (ocr?.pages.find(p => p.number === page)?.words || []).filter(isReadableWord)
}

function contains(r: PanelRectDto, x: number, y: number): boolean {
  return x >= r.x && x <= r.x + r.width && y >= r.y && y <= r.y + r.height
}

function distance(r: PanelRectDto, x: number, y: number): number {
  const dx = Math.max(r.x - x, 0, x - (r.x + r.width))
  const dy = Math.max(r.y - y, 0, y - (r.y + r.height))
  return Math.hypot(dx, dy)
}

/**
 * Index of the panel a word belongs to: the first panel (in reading order) containing the word's center,
 * else the closest panel, so that captions sitting in the gutters still count.
 */
export function panelIndexOfWord(w: OcrWordDto, panels: PanelRectDto[]): number {
  const cx = w.x + w.w / 2
  const cy = w.y + w.h / 2
  const inside = panels.findIndex(p => contains(p, cx, cy))
  if (inside >= 0) return inside
  let best = -1
  let bestDistance = Infinity
  panels.forEach((p, i) => {
    const d = distance(p, cx, cy)
    if (d < bestDistance) {
      bestDistance = d
      best = i
    }
  })
  return best
}

function isPanelStep(step: AutoAdvanceStep): step is AutoAdvanceStep & { panels: PanelRectDto[], panelIndex: number } {
  return step.panels !== undefined && step.panels.length > 0 && step.panelIndex !== undefined
}

export function countWords(ocr: BookOcrDto | null | undefined, step: AutoAdvanceStep): number {
  if (isPanelStep(step)) {
    const index = step.panelIndex
    return step.pages
      .flatMap(p => wordsOnPage(ocr, p))
      .filter(w => panelIndexOfWord(w, step.panels) === index)
      .length
  }
  return step.pages.reduce((sum, p) => sum + wordsOnPage(ocr, p).length, 0)
}

export function stepDuration(words: number, step: AutoAdvanceStep, settings: AutoAdvanceSettings): number {
  const baseline = isPanelStep(step) ? settings.panelBaselineMs : settings.pageBaselineMs
  const msPerWord = 60000 / Math.max(1, settings.wordsPerMinute)
  const duration = baseline + words * msPerWord
  return Math.round(Math.min(AUTO_ADVANCE_MAX_MS, Math.max(AUTO_ADVANCE_MIN_MS, duration)))
}
