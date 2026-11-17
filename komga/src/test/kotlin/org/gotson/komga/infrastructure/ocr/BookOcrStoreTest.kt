package org.gotson.komga.infrastructure.ocr

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeBookPage
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BookOcrStoreTest {
  @TempDir
  lateinit var configDir: Path

  private val book = makeBook("book").copy(fileSize = 1234, fileHash = "abcd")
  private val media = Media(bookId = book.id, pages = listOf(makeBookPage("1.png"), makeBookPage("2.png")))

  private fun store() =
    BookOcrStore(
      KomgaProperties().apply { configDir = this@BookOcrStoreTest.configDir.toString() },
      ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()),
    )

  private fun writeOcr(
    bookId: String = book.id,
    fileSize: Long = 1234,
    fileHash: String = "abcd",
    pageCount: Int = 2,
  ) {
    configDir.resolve("ocr").createDirectories().resolve("${book.id}.json").writeText(
      """
      {"bookId":"$bookId","engine":"apple-vision-tiled-1","fileSize":$fileSize,"fileHash":"$fileHash","pageCount":$pageCount,"extra":1,
       "pages":[{"number":1,"words":[{"x":0.1,"y":0.2,"w":0.05,"h":0.01,"conf":0.5,"text":"HELLO"}]},{"number":2,"words":[]}]}
      """.trimIndent(),
    )
  }

  @Test
  fun `given a current OCR file when finding then it is returned`() {
    writeOcr()

    val ocr = store().find(book, media)

    assertThat(ocr).isNotNull
    assertThat(ocr!!.pages).hasSize(2)
    assertThat(
      ocr.pages[0]
        .words
        .single()
        .text,
    ).isEqualTo("HELLO")
    assertThat(
      ocr.pages[0]
        .words
        .single()
        .x,
    ).isEqualTo(0.1)
  }

  @Test
  fun `given no OCR file when finding then null is returned`() {
    assertThat(store().find(book, media)).isNull()
  }

  @Test
  fun `given an OCR file for another version of the book when finding then null is returned`() {
    writeOcr(fileSize = 999)
    assertThat(store().find(book, media)).isNull()

    writeOcr(fileHash = "other")
    assertThat(store().find(book, media)).isNull()

    writeOcr(pageCount = 3)
    assertThat(store().find(book, media)).isNull()

    writeOcr(bookId = "other")
    assertThat(store().find(book, media)).isNull()
  }

  @Test
  fun `given a corrupt OCR file when finding then null is returned`() {
    configDir
      .resolve("ocr")
      .createDirectories()
      .resolve("${book.id}.json")
      .writeText("{not json")

    assertThat(store().find(book, media)).isNull()
  }
}
