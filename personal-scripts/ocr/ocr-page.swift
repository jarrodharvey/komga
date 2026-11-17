// OCR comic pages with Apple Vision and print one JSON object per image (JSON Lines) on stdout:
//   {"path":"…","width":W,"height":H,"words":[{"x":…,"y":…,"w":…,"h":…,"conf":…,"text":"…"}]}
// Coordinates are relative to the page dimensions ([0,1]) with a top-left origin, matching the
// panel coordinates returned by Komga's panels endpoint.
// Build: swiftc -O ocr-page.swift -o ocr-page
// Usage: ocr-page image1.jpg [image2.png …]
import Foundation
import Vision
import ImageIO

struct Word: Encodable { let x, y, w, h: Double; let conf: Float; let text: String }
struct PageResult: Encodable {
  let path: String; let width: Int; let height: Int; let words: [Word]; let error: String?
}

func rect(_ r: CGRect) -> (Double, Double, Double, Double) {
  // Vision uses a bottom-left origin; flip to top-left.
  (Double(r.minX), Double(1 - r.maxY), Double(r.width), Double(r.height))
}

/// Recognise text inside `roi` (Vision coordinates) and return words with boxes in full-page Vision coordinates.
func recognise(_ image: CGImage, roi: CGRect) -> [(CGRect, Float, String)] {
  let request = VNRecognizeTextRequest()
  request.recognitionLevel = .accurate
  request.usesLanguageCorrection = true
  request.regionOfInterest = roi
  guard (try? VNImageRequestHandler(cgImage: image, options: [:]).perform([request])) != nil else { return [] }
  // Boxes are relative to the region of interest; map them back to the full page.
  func toPage(_ r: CGRect) -> CGRect {
    CGRect(x: roi.minX + r.minX * roi.width, y: roi.minY + r.minY * roi.height,
           width: r.width * roi.width, height: r.height * roi.height)
  }
  var words: [(CGRect, Float, String)] = []
  for obs in request.results ?? [] {
    guard let cand = obs.topCandidates(1).first else { continue }
    let s = cand.string
    let lineBox = obs.boundingBox
    // Split the line into words and get a box for each; fall back to a proportional slice of the line box.
    s.enumerateSubstrings(in: s.startIndex..<s.endIndex, options: .byWords) { sub, range, _, _ in
      guard let sub = sub, !sub.isEmpty else { return }
      var box: CGRect
      if let b = try? cand.boundingBox(for: range)?.boundingBox, b.width > 0, b.height > 0 {
        box = b
      } else {
        let total = Double(s.count)
        let start = Double(s.distance(from: s.startIndex, to: range.lowerBound)) / total
        let len = Double(s.distance(from: range.lowerBound, to: range.upperBound)) / total
        box = CGRect(x: lineBox.minX + lineBox.width * start, y: lineBox.minY,
                     width: lineBox.width * len, height: lineBox.height)
      }
      words.append((toPage(box), cand.confidence, sub))
    }
  }
  return words
}

/// Vision downsamples large pages and misses small lettering, so besides a full-page pass we OCR
/// overlapping half-page bands along the long axis, then drop duplicate words found by several passes.
func regions(width: Int, height: Int) -> [CGRect] {
  var r = [CGRect(x: 0, y: 0, width: 1, height: 1)]
  for start in [0.0, 0.25, 0.5] {
    r.append(height >= width
      ? CGRect(x: 0, y: start, width: 1, height: 0.5)
      : CGRect(x: start, y: 0, width: 0.5, height: 1))
  }
  return r
}

func dedupe(_ words: [(CGRect, Float, String)]) -> [(CGRect, Float, String)] {
  var kept: [(CGRect, Float, String)] = []
  // Prefer confident words; a word overlapping a kept one by more than half of the smaller box is a duplicate.
  for w in words.sorted(by: { $0.1 > $1.1 }) {
    let dup = kept.contains { k in
      let i = k.0.intersection(w.0)
      guard !i.isNull else { return false }
      let smaller = min(k.0.width * k.0.height, w.0.width * w.0.height)
      return smaller > 0 && (i.width * i.height) / smaller > 0.5
    }
    if !dup { kept.append(w) }
  }
  return kept
}

func ocr(_ path: String) -> PageResult {
  let url = URL(fileURLWithPath: path)
  guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
        let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
    return PageResult(path: path, width: 0, height: 0, words: [], error: "cannot decode image")
  }
  let all = regions(width: image.width, height: image.height).flatMap { recognise(image, roi: $0) }
  let words = dedupe(all)
    .sorted { ($0.0.maxY, -$0.0.minX) > ($1.0.maxY, -$1.0.minX) } // top-to-bottom, left-to-right
    .map { w -> Word in
      let (x, y, ww, h) = rect(w.0)
      return Word(x: x, y: y, w: ww, h: h, conf: w.1, text: w.2)
    }
  return PageResult(path: path, width: image.width, height: image.height, words: words, error: nil)
}

let encoder = JSONEncoder()
for path in CommandLine.arguments.dropFirst() {
  let result = autoreleasepool { ocr(path) }
  if let data = try? encoder.encode(result), let line = String(data: data, encoding: .utf8) {
    print(line)
    fflush(stdout)
  }
}
