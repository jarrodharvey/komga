package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.BookOcr

data class BookOcrDto(
  val pages: List<PageOcrDto>,
)

data class PageOcrDto(
  val number: Int,
  val words: List<OcrWordDto>,
)

data class OcrWordDto(
  val x: Double,
  val y: Double,
  val w: Double,
  val h: Double,
  val conf: Double,
)

fun BookOcr.toDto() =
  BookOcrDto(
    pages =
      pages.map { page ->
        PageOcrDto(page.number, page.words.map { OcrWordDto(it.x, it.y, it.w, it.h, it.conf) })
      },
  )
