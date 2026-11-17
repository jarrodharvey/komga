<template>
  <div
    class="guided-reader"
    data-testid="guided-reader"
    :data-page="page"
    :data-panel-index="panelIndex"
    :data-panel-count="steps.length"
    :data-loading="!currentLoaded"
    v-touch="{
               left: () => {if(swipe) {turnRight()}},
               right: () => {if(swipe) {turnLeft()}},
               up: () => {if(swipe) {verticalNext()}},
               down: () => {if(swipe) {verticalPrev()}}
             }"
  >
    <div class="guided-viewport" ref="viewport">
      <img v-if="currentPage"
           :key="`guided-${currentPage.number}`"
           :alt="`Page ${currentPage.number}`"
           :src="currentPage.url"
           class="guided-image"
           :class="{'guided-animate': animations && imageReady && currentLoaded}"
           :style="imageStyle"
           @load="onImageLoad"
      />

      <!--  mask everything outside of the current panel  -->
      <div v-if="imageReady && steps.length > 0"
           class="guided-mask"
           :class="{'guided-animate': animations}"
           :style="maskStyle"
      />
    </div>

    <!--  clickable zone: left  -->
    <div v-if="!vertical"
         @click="turnLeft()"
         class="left-quarter"
         style="z-index: 1;"
    />

    <!--  clickable zone: right  -->
    <div v-if="!vertical"
         @click="turnRight()"
         class="right-quarter"
         style="z-index: 1;"
    />

    <!--  clickable zone: top  -->
    <div v-if="vertical"
         @click="verticalPrev()"
         class="top-quarter"
         style="z-index: 1;"
    />

    <!--  clickable zone: bottom  -->
    <div v-if="vertical"
         @click="verticalNext()"
         class="bottom-quarter"
         style="z-index: 1;"
    />

    <!--  clickable zone: menu  -->
    <div @click="centerClick()"
         :class="`${vertical ? 'center-vertical' : 'center-horizontal'}`"
         style="z-index: 1;"
    />
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {ReadingDirection} from '@/types/enum-books'
import {shortcutsLTR, shortcutsRTL, shortcutsVertical} from '@/functions/shortcuts/paged-reader'
import {PageDtoWithUrl, PanelRectDto} from '@/types/komga-books'
import {AutoAdvanceStep} from '@/functions/auto-advance'

const FULL_PAGE: PanelRectDto = {x: 0, y: 0, width: 1, height: 1}
// fraction of the viewport left around a zoomed panel
const PANEL_MARGIN = 0.04

type PendingPosition = 'first' | 'last'

export default Vue.extend({
  name: 'GuidedReader',
  data: function () {
    return {
      logger: 'GuidedReader',
      // panels per page number, in reading order
      panels: {} as Record<number, PanelRectDto[]>,
      // keys of panel requests in flight, to avoid duplicate requests
      loading: new Set<string>(),
      // index of the current panel. A page without panels is shown whole at index 0.
      panelIndex: 0,
      pending: 'first' as PendingPosition,
      natural: {width: 0, height: 0},
      imageReady: false,
      viewport: {width: window.innerWidth, height: window.innerHeight},
    }
  },
  props: {
    bookId: {
      type: String,
      required: true,
    },
    pages: {
      type: Array as () => PageDtoWithUrl[],
      required: true,
    },
    page: {
      type: Number,
      required: true,
    },
    animations: {
      type: Boolean,
      required: true,
    },
    swipe: {
      type: Boolean,
      required: true,
    },
    readingDirection: {
      type: String as () => ReadingDirection,
      required: true,
    },
    background: {
      type: String,
      default: 'black',
    },
  },
  watch: {
    page: {
      handler(val: number, old: number) {
        this.$debug('[watch:page]', `old:${old}`, `new:${val}`)
        if (val !== old) {
          this.imageReady = false
          this.applyPending()
          this.loadPanels(val)
          this.loadPanels(val + 1)
        }
      },
      immediate: true,
    },
    bookId() {
      this.panels = {}
    },
    autoStep(val: AutoAdvanceStep | null, old: AutoAdvanceStep | null) {
      if (val?.key !== old?.key) this.$emit('step', val)
    },
    readingDirection() {
      // panel order depends on the reading direction
      this.panels = {}
      this.panelIndex = 0
      this.loadPanels(this.page)
      this.loadPanels(this.page + 1)
    },
  },
  created() {
    window.addEventListener('keydown', this.keyPressed)
    window.addEventListener('resize', this.onResize)
  },
  destroyed() {
    window.removeEventListener('keydown', this.keyPressed)
    window.removeEventListener('resize', this.onResize)
    this.$emit('step', null)
  },
  computed: {
    shortcuts(): any {
      const shortcuts = []
      switch (this.readingDirection) {
        case ReadingDirection.RIGHT_TO_LEFT:
          shortcuts.push(...shortcutsRTL)
          break
        case ReadingDirection.VERTICAL:
          shortcuts.push(...shortcutsVertical)
          break
        default:
          shortcuts.push(...shortcutsLTR)
      }
      return this.$_.keyBy(shortcuts, x => x.key)
    },
    flipDirection(): boolean {
      return this.readingDirection === ReadingDirection.RIGHT_TO_LEFT
    },
    vertical(): boolean {
      return this.readingDirection === ReadingDirection.VERTICAL
    },
    currentPage(): PageDtoWithUrl | undefined {
      return this.pages[this.page - 1]
    },
    currentLoaded(): boolean {
      return this.panels[this.page] !== undefined
    },
    // the panels to step through. A page detected as a single full page panel has none, and is shown whole.
    steps(): PanelRectDto[] {
      const panels = this.panels[this.page] || []
      if (panels.length === 1 && this.isFullPage(panels[0])) return []
      return panels
    },
    target(): PanelRectDto {
      return this.panelIndex < this.steps.length ? this.steps[this.panelIndex] : FULL_PAGE
    },
    // transform mapping the image so that the target rectangle is centered and fits the viewport
    transform(): { scale: number, x: number, y: number } {
      const iw = this.natural.width || 1
      const ih = this.natural.height || 1
      const vw = this.viewport.width
      const vh = this.viewport.height
      const margin = this.steps.length > 0 ? PANEL_MARGIN : 0
      const rw = this.target.width * iw
      const rh = this.target.height * ih
      const scale = Math.min(vw * (1 - margin) / rw, vh * (1 - margin) / rh)
      const cx = (this.target.x + this.target.width / 2) * iw
      const cy = (this.target.y + this.target.height / 2) * ih
      return {
        scale: scale,
        x: vw / 2 - cx * scale,
        y: vh / 2 - cy * scale,
      }
    },
    imageStyle(): object {
      const t = this.transform
      return {
        width: this.natural.width ? `${this.natural.width}px` : undefined,
        height: this.natural.height ? `${this.natural.height}px` : undefined,
        transform: `translate(${t.x}px, ${t.y}px) scale(${t.scale})`,
        visibility: this.imageReady && this.currentLoaded ? 'visible' : 'hidden',
      }
    },
    // what is on screen, for auto-advance. Null while the page or its panels are loading.
    autoStep(): AutoAdvanceStep | null {
      if (!this.currentPage || !this.currentLoaded || !this.imageReady) return null
      const panelIndex = this.steps.length > 0 ? this.panelIndex : undefined
      return {
        key: `${this.currentPage.url}#${panelIndex ?? 'page'}`,
        pages: [this.currentPage.number],
        panels: this.steps.length > 0 ? this.steps : undefined,
        panelIndex: panelIndex,
      }
    },
    maskStyle(): object {
      const t = this.transform
      return {
        left: `${t.x + this.target.x * this.natural.width * t.scale}px`,
        top: `${t.y + this.target.y * this.natural.height * t.scale}px`,
        width: `${this.target.width * this.natural.width * t.scale}px`,
        height: `${this.target.height * this.natural.height * t.scale}px`,
        boxShadow: `0 0 0 200vmax ${this.background}`,
      }
    },
  },
  methods: {
    keyPressed(e: KeyboardEvent) {
      if (e.ctrlKey || e.altKey || e.shiftKey || e.metaKey) return
      this.shortcuts[e.key]?.execute(this)
    },
    onResize() {
      this.viewport = {width: window.innerWidth, height: window.innerHeight}
    },
    onImageLoad(e: Event) {
      const img = e.target as HTMLImageElement
      this.natural = {width: img.naturalWidth, height: img.naturalHeight}
      this.onResize()
      this.imageReady = true
    },
    isFullPage(p: PanelRectDto): boolean {
      return p.x <= 0.01 && p.y <= 0.01 && p.width >= 0.98 && p.height >= 0.98
    },
    async loadPanels(pageNumber: number) {
      if (pageNumber < 1 || pageNumber > this.pages.length) return
      if (this.panels[pageNumber] !== undefined) return
      const bookId = this.bookId
      const readingDirection = this.readingDirection
      const key = `${bookId}:${pageNumber}:${readingDirection}`
      if (this.loading.has(key)) return
      this.loading.add(key)
      let panels: PanelRectDto[]
      try {
        panels = (await this.$komgaBooks.getPagePanels(bookId, pageNumber, readingDirection)).panels
      } catch (e) {
        this.$debug('[loadPanels]', `page:${pageNumber}`, e)
        panels = [FULL_PAGE]
      } finally {
        this.loading.delete(key)
      }
      // discard results if the context changed while loading
      if (bookId !== this.bookId || readingDirection !== this.readingDirection) return
      this.$set(this.panels, pageNumber, panels.length > 0 ? panels : [FULL_PAGE])
      if (pageNumber === this.page) this.applyPending()
    },
    // position the panel index after a page change, once the panels are known
    applyPending() {
      if (this.pending === 'last') {
        if (!this.currentLoaded) {
          this.panelIndex = 0
          return
        }
        this.panelIndex = Math.max(0, this.steps.length - 1)
      } else {
        this.panelIndex = 0
      }
      this.pending = 'first'
    },
    centerClick() {
      this.$emit('menu')
    },
    turnRight() {
      if (!this.vertical)
        this.flipDirection ? this.prev() : this.next()
    },
    turnLeft() {
      if (!this.vertical)
        this.flipDirection ? this.next() : this.prev()
    },
    verticalPrev() {
      if (this.vertical) this.prev()
    },
    verticalNext() {
      if (this.vertical) this.next()
    },
    next() {
      // wait for the panels of the current page, else we would skip it
      if (!this.currentLoaded) return
      if (this.panelIndex < this.steps.length - 1) {
        this.panelIndex++
      } else if (this.page < this.pages.length) {
        this.pending = 'first'
        this.$emit('update:page', this.page + 1)
      } else {
        this.$emit('jump-next')
      }
    },
    prev() {
      if (this.panelIndex > 0) {
        this.panelIndex--
      } else if (this.page > 1) {
        this.pending = 'last'
        this.$emit('update:page', this.page - 1)
      } else {
        this.$emit('jump-previous')
      }
    },
  },
})
</script>
<style scoped>
.guided-reader {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
}

.guided-viewport {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.guided-image {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  transform-origin: 0 0;
}

.guided-mask {
  position: absolute;
  pointer-events: none;
}

.guided-animate {
  transition: transform 0.35s ease-in-out, left 0.35s ease-in-out, top 0.35s ease-in-out, width 0.35s ease-in-out, height 0.35s ease-in-out;
}

.left-quarter {
  top: 0;
  left: 0;
  width: 25%;
  height: 100%;
  position: absolute;
}

.right-quarter {
  top: 0;
  right: 0;
  width: 25%;
  height: 100%;
  position: absolute;
}

.top-quarter {
  top: 0;
  height: 25%;
  width: 100%;
  position: absolute;
}

.bottom-quarter {
  bottom: 0;
  height: 25%;
  width: 100%;
  position: absolute;
}

.center-horizontal {
  top: 0;
  left: 25%;
  width: 50%;
  height: 100%;
  position: absolute;
}

.center-vertical {
  top: 25%;
  height: 50%;
  width: 100%;
  position: absolute;
}
</style>
