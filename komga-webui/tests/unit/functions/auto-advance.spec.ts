import {
  AUTO_ADVANCE_DEFAULTS,
  AUTO_ADVANCE_MAX_MS,
  AUTO_ADVANCE_MIN_MS,
  countWords,
  panelIndexOfWord,
  stepDuration,
} from '@/functions/auto-advance'
import {BookOcrDto, OcrWordDto, PanelRectDto} from '@/types/komga-books'

function word(x: number, y: number, extra: Partial<OcrWordDto> = {}): OcrWordDto {
  return {x, y, w: 0.04, h: 0.01, conf: 1, ...extra}
}

// two panels side by side on the top half, one panel on the bottom half
const panels: PanelRectDto[] = [
  {x: 0, y: 0, width: 0.48, height: 0.48},
  {x: 0.52, y: 0, width: 0.48, height: 0.48},
  {x: 0, y: 0.52, width: 1, height: 0.48},
]

const ocr: BookOcrDto = {
  pages: [
    {
      number: 1,
      words: [
        word(0.1, 0.1), word(0.2, 0.1), // panel 0
        word(0.6, 0.1), // panel 1
        word(0.3, 0.7), word(0.4, 0.7), word(0.5, 0.7), // panel 2
        word(0.6, 0.505), // in the gutter, closest to panel 2
        word(0.1, 0.2, {conf: 0.1}), // noise
        word(0.1, 0.3, {h: 0.2}), // sound effect
      ],
    },
    {number: 2, words: [word(0.5, 0.5), word(0.5, 0.6)]},
  ],
}

describe('panelIndexOfWord', () => {
  test('given a word inside a panel then it belongs to that panel', () => {
    expect(panelIndexOfWord(word(0.6, 0.1), panels)).toEqual(1)
  })

  test('given a word in a gutter then it belongs to the closest panel', () => {
    expect(panelIndexOfWord(word(0.6, 0.505), panels)).toEqual(2)
  })

  test('given no panels then it belongs to none', () => {
    expect(panelIndexOfWord(word(0.6, 0.1), [])).toEqual(-1)
  })
})

describe('countWords', () => {
  test('given a page then it counts the readable words of the page', () => {
    expect(countWords(ocr, {key: '1', pages: [1]})).toEqual(7)
  })

  test('given a double page spread then it counts the words of both pages', () => {
    expect(countWords(ocr, {key: '1-2', pages: [1, 2]})).toEqual(9)
  })

  test('given a panel then it counts the words of the panel only', () => {
    expect(countWords(ocr, {key: 'p0', pages: [1], panels, panelIndex: 0})).toEqual(2)
    expect(countWords(ocr, {key: 'p1', pages: [1], panels, panelIndex: 1})).toEqual(1)
    expect(countWords(ocr, {key: 'p2', pages: [1], panels, panelIndex: 2})).toEqual(4)
  })

  test('given no OCR then it counts no words', () => {
    expect(countWords(null, {key: '1', pages: [1]})).toEqual(0)
    expect(countWords(ocr, {key: '3', pages: [3]})).toEqual(0)
  })
})

describe('stepDuration', () => {
  const settings = {...AUTO_ADVANCE_DEFAULTS, pageBaselineMs: 5000, panelBaselineMs: 2000, wordsPerMinute: 240}

  test('given a page then it is the page baseline plus the reading time of the words', () => {
    expect(stepDuration(0, {key: '1', pages: [1]}, settings)).toEqual(5000)
    expect(stepDuration(20, {key: '1', pages: [1]}, settings)).toEqual(10000)
  })

  test('given a panel then it uses the panel baseline', () => {
    expect(stepDuration(4, {key: 'p', pages: [1], panels, panelIndex: 0}, settings)).toEqual(3000)
  })

  test('given extreme values then the duration is clamped', () => {
    expect(stepDuration(0, {key: '1', pages: [1]}, {...settings, pageBaselineMs: 0})).toEqual(AUTO_ADVANCE_MIN_MS)
    expect(stepDuration(10000, {key: '1', pages: [1]}, settings)).toEqual(AUTO_ADVANCE_MAX_MS)
  })
})
