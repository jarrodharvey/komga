package org.gotson.komga.infrastructure.ocr

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookOcr
import org.gotson.komga.domain.model.Media
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

private val logger = KotlinLogging.logger {}

/**
 * Reads the OCR files written by personal-scripts/ocr/ocr-library.py, stored as `<config-dir>/ocr/<bookId>.json`.
 */
@Service
class BookOcrStore(
  komgaProperties: KomgaProperties,
  objectMapper: ObjectMapper,
) {
  private val directory: Path? = komgaProperties.configDir?.let { Path.of(it, "ocr") }
  private val mapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  /**
   * Returns the OCR data for [book], or null when there is none or it was made for a different version of the file.
   */
  fun find(
    book: Book,
    media: Media,
  ): BookOcr? {
    val file = directory?.resolve("${book.id}.json")?.takeIf { it.exists() } ?: return null
    val ocr =
      try {
        file.inputStream().use { mapper.readValue(it, BookOcr::class.java) }
      } catch (e: Exception) {
        logger.warn(e) { "Could not read OCR file $file" }
        return null
      }
    if (ocr.bookId != book.id || ocr.pageCount != media.pageCount || ocr.fileSize != book.fileSize || ocr.fileHash != book.fileHash) {
      logger.debug { "OCR file $file is stale for $book" }
      return null
    }
    return ocr
  }
}
