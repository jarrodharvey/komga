import {KeepAwake} from '@/functions/keep-awake'

const mock = {
  enabled: false,
  // whether the next enable() call succeeds, like a video play() with or without a user gesture
  allow: true,
  enableCalls: 0,
}

jest.mock('nosleep.js', () => jest.fn().mockImplementation(() => ({
  get isEnabled() {
    return mock.enabled
  },
  enable: jest.fn(() => {
    mock.enableCalls++
    if (!mock.allow) return Promise.reject(new Error('NotAllowedError'))
    mock.enabled = true
    return Promise.resolve()
  }),
  disable: jest.fn(() => mock.enabled = false),
})))

const flush = () => new Promise(r => setTimeout(r, 0))

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', {configurable: true, get: () => hidden})
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('KeepAwake', () => {
  let lock: KeepAwake

  beforeEach(() => {
    mock.enabled = false
    mock.allow = true
    mock.enableCalls = 0
    setHidden(false)
    lock = new KeepAwake()
  })

  afterEach(() => lock.destroy())

  it('holds the screen while wanted', async () => {
    lock.set(true)
    await flush()
    expect(mock.enabled).toBe(true)
    lock.set(false)
    expect(mock.enabled).toBe(false)
  })

  it('retries on the next gesture when refused', async () => {
    mock.allow = false
    lock.set(true)
    await flush()
    expect(mock.enabled).toBe(false)

    mock.allow = true
    window.dispatchEvent(new Event('click'))
    await flush()
    expect(mock.enabled).toBe(true)
  })

  it('does nothing on gestures while not wanted', async () => {
    window.dispatchEvent(new Event('click'))
    await flush()
    expect(mock.enableCalls).toBe(0)
  })

  it('releases while hidden and takes the screen again when visible', async () => {
    lock.set(true)
    await flush()
    setHidden(true)
    expect(mock.enabled).toBe(false)
    setHidden(false)
    await flush()
    expect(mock.enabled).toBe(true)
  })

  it('releases when turned off while a request is pending', async () => {
    lock.set(true)
    lock.set(false)
    await flush()
    expect(mock.enabled).toBe(false)
  })

  it('stops listening once destroyed', async () => {
    mock.allow = false
    lock.set(true)
    await flush()
    lock.destroy()
    mock.allow = true
    window.dispatchEvent(new Event('click'))
    await flush()
    expect(mock.enabled).toBe(false)
  })
})
