package org.gotson.komga.infrastructure.image

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.gotson.komga.domain.model.PanelRect
import org.junit.jupiter.api.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class PanelDetectorTest {
  private val detector = PanelDetector()
  private val offset = Offset.offset(0.02)

  private val topLeft = Rectangle(40, 40, 350, 520)
  private val topRight = Rectangle(410, 40, 350, 520)
  private val bottom = Rectangle(40, 600, 720, 560)

  private fun page(
    panels: List<Rectangle>,
    gutter: Color = Color.WHITE,
    border: Color = Color.BLACK,
    decorate: (java.awt.Graphics2D) -> Unit = {},
  ): BufferedImage {
    val image = BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = gutter
    g.fillRect(0, 0, image.width, image.height)
    val random = Random(42)
    panels.forEach { p ->
      g.color = Color(200, 210, 230)
      g.fillRect(p.x, p.y, p.width, p.height)
      // some "artwork"
      repeat(30) {
        g.color = Color(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        g.fillOval(p.x + random.nextInt(p.width - 40), p.y + random.nextInt(p.height - 40), 40, 40)
      }
      g.color = border
      g.stroke = BasicStroke(4f)
      g.drawRect(p.x, p.y, p.width, p.height)
    }
    decorate(g)
    g.dispose()
    return image
  }

  private fun assertMatches(
    actual: PanelRect,
    expected: Rectangle,
  ) {
    assertThat(actual.x).isCloseTo(expected.x / 800.0, offset)
    assertThat(actual.y).isCloseTo(expected.y / 1200.0, offset)
    assertThat(actual.width).isCloseTo(expected.width / 800.0, offset)
    assertThat(actual.height).isCloseTo(expected.height / 1200.0, offset)
  }

  @Test
  fun `given simple grid when detecting left to right then panels are found in reading order`() {
    val result = detector.detectPanels(page(listOf(bottom, topRight, topLeft)), rightToLeft = false)

    assertThat(result.width).isEqualTo(800)
    assertThat(result.height).isEqualTo(1200)
    assertThat(result.panels).hasSize(3)
    assertMatches(result.panels[0], topLeft)
    assertMatches(result.panels[1], topRight)
    assertMatches(result.panels[2], bottom)
  }

  @Test
  fun `given simple grid when detecting right to left then panels in a row are reversed`() {
    val result = detector.detectPanels(page(listOf(topLeft, topRight, bottom)), rightToLeft = true)

    assertThat(result.panels).hasSize(3)
    assertMatches(result.panels[0], topRight)
    assertMatches(result.panels[1], topLeft)
    assertMatches(result.panels[2], bottom)
  }

  @Test
  fun `given encoded image bytes when detecting then panels are found`() {
    val bytes =
      ByteArrayOutputStream().use {
        ImageIO.write(page(listOf(topLeft, topRight, bottom)), "png", it)
        it.toByteArray()
      }

    assertThat(detector.detectPanels(bytes, rightToLeft = false).panels).hasSize(3)
  }

  @Test
  fun `given speech balloon crossing a gutter when detecting then panels are still split`() {
    val image =
      page(listOf(topLeft, topRight, bottom)) { g ->
        g.color = Color.WHITE
        g.fillOval(300, 200, 200, 100)
        g.color = Color.BLACK
        g.stroke = BasicStroke(3f)
        g.drawOval(300, 200, 200, 100)
      }

    val result = detector.detectPanels(image, rightToLeft = false)

    assertThat(result.panels).hasSize(3)
    assertThat(result.panels[0].x + result.panels[0].width).isLessThan(0.52)
    assertThat(result.panels[1].x).isGreaterThan(0.48)
    assertMatches(result.panels[2], bottom)
  }

  @Test
  fun `given dark gutters when detecting then panels are found`() {
    val result = detector.detectPanels(page(listOf(topLeft, topRight, bottom), gutter = Color.BLACK, border = Color.WHITE), rightToLeft = false)

    assertThat(result.panels).hasSize(3)
    assertMatches(result.panels[0], topLeft)
    assertMatches(result.panels[1], topRight)
    assertMatches(result.panels[2], bottom)
  }

  @Test
  fun `given tall panel next to stacked panels when detecting then column is read before moving on`() {
    val tall = Rectangle(40, 40, 350, 1120)
    val rightTop = Rectangle(410, 40, 350, 540)
    val rightBottom = Rectangle(410, 620, 350, 540)

    val ltr = detector.detectPanels(page(listOf(rightBottom, tall, rightTop)), rightToLeft = false)
    assertThat(ltr.panels).hasSize(3)
    assertMatches(ltr.panels[0], tall)
    assertMatches(ltr.panels[1], rightTop)
    assertMatches(ltr.panels[2], rightBottom)

    val rtl = detector.detectPanels(page(listOf(rightBottom, tall, rightTop)), rightToLeft = true)
    assertMatches(rtl.panels[0], rightTop)
    assertMatches(rtl.panels[1], rightBottom)
    assertMatches(rtl.panels[2], tall)
  }

  @Test
  fun `given full page artwork when detecting then single full page panel is returned`() {
    val result = detector.detectPanels(page(listOf(Rectangle(0, 0, 800, 1200))), rightToLeft = false)

    assertThat(result.panels).containsExactly(PanelDetector.FULL_PAGE)
  }

  @Test
  fun `given full bleed gradient artwork when detecting then single full page panel is returned`() {
    val image = BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.paint = java.awt.GradientPaint(0f, 0f, Color.ORANGE, 0f, 1200f, Color(128, 0, 128))
    g.fillRect(0, 0, 800, 1200)
    g.dispose()

    val result = detector.detectPanels(image, rightToLeft = false)

    assertThat(result.panels).containsExactly(PanelDetector.FULL_PAGE)
  }

  @Test
  fun `given invalid bytes when detecting then single full page panel is returned`() {
    val result = detector.detectPanels(byteArrayOf(1, 2, 3), rightToLeft = false)

    assertThat(result.panels).containsExactly(PanelDetector.FULL_PAGE)
  }
}
