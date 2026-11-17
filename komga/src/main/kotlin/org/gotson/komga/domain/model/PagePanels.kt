package org.gotson.komga.domain.model

/**
 * Panels detected on a page, in reading order.
 * All coordinates are relative to the page dimensions, in the range [0, 1].
 */
data class PagePanels(
  val width: Int,
  val height: Int,
  val panels: List<PanelRect>,
)

data class PanelRect(
  val x: Double,
  val y: Double,
  val width: Double,
  val height: Double,
)
