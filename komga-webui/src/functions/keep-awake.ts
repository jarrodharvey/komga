import NoSleep from 'nosleep.js'

// Keeps the screen from sleeping while wanted.
// Uses the Screen Wake Lock API where available (HTTPS only on iOS), otherwise a silent looping
// video, which iOS only starts from a user gesture: until it runs, every tap or key press retries.
export class KeepAwake {
  private noSleep: NoSleep | null = null
  private wanted = false
  private pending = false

  constructor() {
    document.addEventListener('visibilitychange', this.visibilityChanged)
    // capture phase, so readers that stop propagation still count as a gesture
    for (const e of ['click', 'touchend', 'keydown']) window.addEventListener(e, this.gesture, true)
  }

  // call synchronously from a user gesture where possible, so the video fallback may start
  set(wanted: boolean) {
    if (wanted === this.wanted) return
    this.wanted = wanted
    if (wanted) this.enable()
    else this.disable()
  }

  destroy() {
    this.set(false)
    document.removeEventListener('visibilitychange', this.visibilityChanged)
    for (const e of ['click', 'touchend', 'keydown']) window.removeEventListener(e, this.gesture, true)
  }

  private enable() {
    if (!this.wanted || document.hidden || this.pending) return
    if (!this.noSleep) this.noSleep = new NoSleep()
    if (this.noSleep.isEnabled) return
    this.pending = true
    this.noSleep.enable()
      .then(() => {
        // turned off while the request was pending
        if (!this.wanted) this.noSleep?.disable()
      })
      // no gesture yet, or the wake lock was refused: retry on the next gesture
      .catch(() => undefined)
      .finally(() => this.pending = false)
  }

  private disable() {
    if (this.noSleep?.isEnabled) this.noSleep.disable()
  }

  // the wake lock is released and the video paused when the page is hidden: start over when it is back
  private visibilityChanged = () => {
    if (document.hidden) this.disable()
    else this.enable()
  }

  private gesture = () => {
    if (this.wanted) this.enable()
  }
}
