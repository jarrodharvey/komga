package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.PagePanels

data class PagePanelsDto(
  val width: Int,
  val height: Int,
  val panels: List<PanelRectDto>,
)

data class PanelRectDto(
  val x: Double,
  val y: Double,
  val width: Double,
  val height: Double,
)

fun PagePanels.toDto() =
  PagePanelsDto(
    width = width,
    height = height,
    panels = panels.map { PanelRectDto(it.x, it.y, it.width, it.height) },
  )
