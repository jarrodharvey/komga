package org.gotson.komga.domain.model

/**
 * Words recognized by OCR on each page of a book, produced offline by personal-scripts/ocr.
 * Word coordinates are relative to the page dimensions, in the range [0, 1], with a top-left origin.
 */
data class BookOcr(
  val bookId: String,
  val engine: String,
  // identify the version of the book file the OCR was made from
  val fileSize: Long,
  val fileHash: String,
  val pageCount: Int,
  val pages: List<PageOcr>,
)

data class PageOcr(
  val number: Int,
  val words: List<OcrWord>,
)

data class OcrWord(
  val x: Double,
  val y: Double,
  val w: Double,
  val h: Double,
  val conf: Double = 1.0,
  val text: String = "",
)
