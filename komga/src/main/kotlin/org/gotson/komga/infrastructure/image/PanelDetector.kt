package org.gotson.komga.infrastructure.image

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.PagePanels
import org.gotson.komga.domain.model.PanelRect
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.springframework.stereotype.Service
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * Detects comic panels on a page image, for the guided view reading mode.
 *
 * Classic contour-based approach:
 * - work on a downscaled grayscale copy of the page
 * - detect the gutter color from the page border
 * - binarize so that anything differing from the gutter color is foreground
 * - take the bounding boxes of the external contours
 * - clean up: drop small boxes, merge contained/overlapping ones, split boxes that still contain gutters
 * - sort the panels in reading order using a recursive XY-cut on the boxes
 */
@Service
class PanelDetector {
  private val cache: Cache<String, PagePanels> =
    Caffeine
      .newBuilder()
      .maximumSize(2000)
      .expireAfterAccess(1, TimeUnit.HOURS)
      .build()

  private val openCvAvailable: Boolean by lazy {
    try {
      nu.pattern.OpenCV.loadLocally()
      logger.info { "OpenCV ${Core.VERSION} loaded for panel detection" }
      true
    } catch (e: Throwable) {
      logger.warn(e) { "Could not load OpenCV, panel detection will be disabled" }
      false
    }
  }

  /**
   * Returns the cached panels for [cacheKey], or detects them from the bytes returned by [imageBytes].
   */
  fun getPanels(
    cacheKey: String,
    rightToLeft: Boolean,
    imageBytes: () -> ByteArray,
  ): PagePanels = cache.get("$cacheKey:$rightToLeft") { detectPanels(imageBytes(), rightToLeft) }

  fun detectPanels(
    bytes: ByteArray,
    rightToLeft: Boolean,
  ): PagePanels {
    val image =
      try {
        ImageIO.read(ByteArrayInputStream(bytes))
      } catch (e: Exception) {
        logger.warn(e) { "Could not decode image for panel detection" }
        null
      } ?: return PagePanels(0, 0, listOf(FULL_PAGE))

    return detectPanels(image, rightToLeft)
  }

  fun detectPanels(
    image: BufferedImage,
    rightToLeft: Boolean,
  ): PagePanels {
    val fallback = PagePanels(image.width, image.height, listOf(FULL_PAGE))
    if (!openCvAvailable) return fallback

    return try {
      val gray = toScaledGray(image)
      val boxes = findPanels(gray)
      val w = gray.width.toDouble()
      val h = gray.height.toDouble()
      val pageArea = w * h

      if (boxes.isEmpty() || (boxes.size == 1 && boxes.first().area >= pageArea * FULL_PAGE_RATIO)) {
        fallback
      } else {
        val ordered = order(boxes, rightToLeft, (w * ORDER_TOLERANCE).roundToInt(), (h * ORDER_TOLERANCE).roundToInt())
        PagePanels(
          image.width,
          image.height,
          ordered.map { PanelRect(it.x / w, it.y / h, it.w / w, it.h / h) },
        )
      }
    } catch (e: Throwable) {
      logger.warn(e) { "Panel detection failed" }
      fallback
    }
  }

  private fun toScaledGray(image: BufferedImage): BufferedImage {
    val scale = min(1.0, MAX_DIMENSION.toDouble() / max(image.width, image.height))
    val w = max(1, (image.width * scale).roundToInt())
    val h = max(1, (image.height * scale).roundToInt())
    return BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY).also {
      val g = it.createGraphics()
      try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, w, h)
        g.drawImage(image, 0, 0, w, h, null)
      } finally {
        g.dispose()
      }
    }
  }

  private fun findPanels(gray: BufferedImage): List<Box> {
    val w = gray.width
    val h = gray.height
    val pixels = (gray.raster.dataBuffer as DataBufferByte).data

    val (background, uniformity) = borderStats(pixels, w, h)
    // artwork bleeding to the page edges: there are no gutters to work with
    if (uniformity < MIN_BORDER_UNIFORMITY) return emptyList()
    val darkGutters = background < 128

    val src = Mat(h, w, CvType.CV_8UC1)
    val binary = Mat()
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    val hierarchy = Mat()
    val contours = mutableListOf<MatOfPoint>()
    try {
      src.put(0, 0, pixels)
      if (darkGutters) {
        Imgproc.threshold(src, binary, min(254, background + THRESHOLD_DELTA).toDouble(), 255.0, Imgproc.THRESH_BINARY)
      } else {
        Imgproc.threshold(src, binary, max(1, background - THRESHOLD_DELTA).toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
      }
      Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

      // copy the binary mask to the JVM, used to split boxes on remaining gutters
      val mask = ByteArray(w * h)
      binary.get(0, 0, mask)

      Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
      val minArea = w * h * MIN_PANEL_AREA_RATIO

      var boxes =
        contours
          .map { Imgproc.boundingRect(it).let { r -> Box(r.x, r.y, r.width, r.height) } }
          .filter { it.area >= minArea }
      boxes = mergeOverlapping(boxes)
      boxes = boxes.flatMap { split(it, mask, w, h, minArea, horizontal = true) }
      boxes = mergeOverlapping(boxes.filter { it.area >= minArea })

      val marginX = (w * PANEL_MARGIN).roundToInt()
      val marginY = (h * PANEL_MARGIN).roundToInt()
      return boxes.map { it.expand(marginX, marginY, w, h) }
    } finally {
      contours.forEach { it.release() }
      hierarchy.release()
      kernel.release()
      binary.release()
      src.release()
    }
  }

  /**
   * Returns the median luminance of the page border, and the fraction of border pixels close to that median.
   */
  private fun borderStats(
    pixels: ByteArray,
    w: Int,
    h: Int,
  ): Pair<Int, Double> {
    val band = max(1, min(w, h) / 200)
    val values = mutableListOf<Int>()
    for (y in 0 until h) {
      for (x in 0 until w) {
        if (x < band || y < band || x >= w - band || y >= h - band) values += pixels[y * w + x].toInt() and 0xFF
      }
    }
    values.sort()
    val median = values[values.size / 2]
    val uniform = values.count { kotlin.math.abs(it - median) < THRESHOLD_DELTA }
    return median to uniform.toDouble() / values.size
  }

  /**
   * Merges boxes contained in another box, or overlapping significantly, until stable.
   */
  private fun mergeOverlapping(input: List<Box>): List<Box> {
    val boxes = input.toMutableList()
    var merged = true
    while (merged) {
      merged = false
      loop@ for (i in boxes.indices) {
        for (j in i + 1 until boxes.size) {
          val a = boxes[i]
          val b = boxes[j]
          val inter = a.intersectionArea(b)
          if (inter > 0 && inter >= MERGE_OVERLAP_RATIO * min(a.area, b.area)) {
            boxes[i] = a.union(b)
            boxes.removeAt(j)
            merged = true
            break@loop
          }
        }
      }
    }
    return boxes
  }

  /**
   * Recursively splits a box along gutter lines that are still inside it.
   * This happens when a speech balloon or artwork crosses a gutter, joining two panels in a single contour.
   *
   * A gutter line is a row (or column) inside the box that is mostly background,
   * and has background at both of its ends (so the vertical borders of a single panel don't qualify).
   */
  private fun split(
    box: Box,
    mask: ByteArray,
    w: Int,
    h: Int,
    minArea: Double,
    horizontal: Boolean,
    triedOther: Boolean = false,
  ): List<Box> {
    val length = if (horizontal) box.w else box.h
    val span = if (horizontal) box.h else box.w
    val edge = max(2, (length * 0.03).roundToInt())
    val minGutter = max(2, ((if (horizontal) h else w) * MIN_GUTTER_RATIO).roundToInt())

    fun isForeground(
      along: Int,
      across: Int,
    ): Boolean {
      val x = if (horizontal) box.x + along else box.x + across
      val y = if (horizontal) box.y + across else box.y + along
      return mask[y * w + x].toInt() != 0
    }

    fun isGutter(across: Int): Boolean {
      for (i in 0 until edge) {
        if (isForeground(i, across) || isForeground(length - 1 - i, across)) return false
      }
      var fg = 0
      for (i in 0 until length) if (isForeground(i, across)) fg++
      return fg <= length * (1 - GUTTER_BACKGROUND_RATIO)
    }

    // find gutter runs strictly inside the box
    val cuts = mutableListOf<IntRange>()
    var start = -1
    for (a in 0 until span) {
      if (isGutter(a)) {
        if (start < 0) start = a
      } else {
        if (start > 0 && a - start >= minGutter) cuts += start until a
        start = -1
      }
    }

    if (cuts.isEmpty()) {
      return if (triedOther) listOf(box) else split(box, mask, w, h, minArea, !horizontal, true)
    }

    val pieces = mutableListOf<Box>()
    var from = 0
    for (cut in cuts + listOf(span until span)) {
      val size = cut.first - from
      if (size > 0) {
        pieces +=
          if (horizontal) {
            Box(box.x, box.y + from, box.w, size)
          } else {
            Box(box.x + from, box.y, size, box.h)
          }
      }
      from = cut.last + 1
    }

    val valid = pieces.filter { it.area >= minArea }
    if (valid.size < 2) {
      return if (triedOther) listOf(box) else split(box, mask, w, h, minArea, !horizontal, true)
    }
    return valid.flatMap { split(it.trim(mask, w), mask, w, h, minArea, !horizontal) }
  }

  /**
   * Orders boxes in reading order with a recursive XY-cut:
   * split into rows (top to bottom) when possible, else into columns (left to right, or right to left).
   */
  private fun order(
    boxes: List<Box>,
    rightToLeft: Boolean,
    tolX: Int,
    tolY: Int,
  ): List<Box> {
    if (boxes.size <= 1) return boxes

    val rows = groups(boxes, { it.y }, { it.y + it.h }, tolY)
    if (rows.size > 1) return rows.flatMap { order(it, rightToLeft, tolX, tolY) }

    val cols = groups(boxes, { it.x }, { it.x + it.w }, tolX).let { if (rightToLeft) it.reversed() else it }
    if (cols.size > 1) return cols.flatMap { order(it, rightToLeft, tolX, tolY) }

    return boxes.sortedWith(compareBy<Box> { it.y }.thenBy { if (rightToLeft) -(it.x + it.w) else it.x })
  }

  private fun groups(
    boxes: List<Box>,
    start: (Box) -> Int,
    end: (Box) -> Int,
    tolerance: Int,
  ): List<List<Box>> {
    val sorted = boxes.sortedBy(start)
    val groups = mutableListOf(mutableListOf(sorted.first()))
    var groupEnd = end(sorted.first())
    for (box in sorted.drop(1)) {
      if (start(box) >= groupEnd - tolerance) {
        groups += mutableListOf(box)
      } else {
        groups.last() += box
      }
      groupEnd = max(groupEnd, end(box))
    }
    return groups
  }

  private data class Box(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
  ) {
    val area: Double get() = w.toDouble() * h

    fun intersectionArea(o: Box): Double {
      val ix = min(x + w, o.x + o.w) - max(x, o.x)
      val iy = min(y + h, o.y + o.h) - max(y, o.y)
      return if (ix > 0 && iy > 0) ix.toDouble() * iy else 0.0
    }

    fun union(o: Box): Box {
      val nx = min(x, o.x)
      val ny = min(y, o.y)
      return Box(nx, ny, max(x + w, o.x + o.w) - nx, max(y + h, o.y + o.h) - ny)
    }

    fun expand(
      mx: Int,
      my: Int,
      maxW: Int,
      maxH: Int,
    ): Box {
      val nx = max(0, x - mx)
      val ny = max(0, y - my)
      return Box(nx, ny, min(maxW, x + w + mx) - nx, min(maxH, y + h + my) - ny)
    }

    /** Shrinks the box to the bounding box of its foreground pixels. */
    fun trim(
      mask: ByteArray,
      stride: Int,
    ): Box {
      var minX = Int.MAX_VALUE
      var minY = Int.MAX_VALUE
      var maxX = -1
      var maxY = -1
      for (yy in y until y + h) {
        for (xx in x until x + w) {
          if (mask[yy * stride + xx].toInt() != 0) {
            if (xx < minX) minX = xx
            if (xx > maxX) maxX = xx
            if (yy < minY) minY = yy
            if (yy > maxY) maxY = yy
          }
        }
      }
      return if (maxX < 0) this else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
  }

  companion object {
    val FULL_PAGE = PanelRect(0.0, 0.0, 1.0, 1.0)

    private const val MAX_DIMENSION = 1000
    private const val THRESHOLD_DELTA = 40
    private const val MIN_BORDER_UNIFORMITY = 0.7
    private const val MIN_PANEL_AREA_RATIO = 0.015
    private const val FULL_PAGE_RATIO = 0.9
    private const val MERGE_OVERLAP_RATIO = 0.5
    private const val MIN_GUTTER_RATIO = 0.004
    private const val GUTTER_BACKGROUND_RATIO = 0.6
    private const val PANEL_MARGIN = 0.005
    private const val ORDER_TOLERANCE = 0.02
  }
}
