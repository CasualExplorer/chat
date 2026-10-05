package main

// The frame cache and input filter follow Crush's
// (github.com/charmbracelet/crush, internal/ui/model/framecache.go and
// filter.go), Copyright 2025-2026 Charmbracelet, Inc., used under
// FSL-1.1-MIT.

import (
	"time"

	tea "charm.land/bubbletea/v2"
)

const (
	// frameCacheTTL bounds how long a frame may be served after it was
	// drawn, in case a change slips past invalidation.
	frameCacheTTL = 3 * time.Second
	// frameCacheMaxEntries bounds the memory the cache holds: a frame is
	// the whole styled screen.
	frameCacheMaxEntries = 32

	// inputFilterInterval lets through about one mouse sample per 60 Hz
	// frame.
	inputFilterInterval = 16 * time.Millisecond
)

// frameGCMsg sweeps expired frames once scrolling has stopped, so they
// aren't kept indefinitely.
type frameGCMsg struct{}

// frameKey identifies a drawn frame. Anything else that changes the frame
// empties the cache, so the key only covers what scrolling changes.
type frameKey struct {
	width, height int
	chat          frameState
}

type frameEntry struct {
	content  string
	cursor   *tea.Cursor
	at       time.Time // drawn, for expiry
	lastUsed time.Time // for eviction
}

// frameCache keeps recently drawn frames by scroll position. Bubble Tea
// draws after every update, so a burst of wheel events would otherwise
// redraw the whole screen for each one, even at the top or bottom where the
// view doesn't move.
//
// Update marks the updates that only scrolled; any other update empties the
// cache when the next frame is drawn. The model is copied on every update,
// so this state lives behind a pointer.
type frameCache struct {
	entries map[frameKey]*frameEntry
	now     func() time.Time

	scrollOnly bool // this update only scrolled the chat
	skipPut    bool // don't keep the next frame: the cache went idle
	gcArmed    bool // a frameGCMsg is on its way
}

func newFrameCache() *frameCache {
	return &frameCache{entries: make(map[frameKey]*frameEntry, frameCacheMaxEntries), now: time.Now}
}

func (c *frameCache) expired(e *frameEntry, now time.Time) bool {
	return now.Sub(e.at) > frameCacheTTL
}

// get returns the frame kept for key, if it hasn't expired.
func (c *frameCache) get(key frameKey) (string, *tea.Cursor, bool) {
	e, ok := c.entries[key]
	if !ok {
		return "", nil, false
	}
	now := c.now()
	if c.expired(e, now) {
		delete(c.entries, key)
		return "", nil, false
	}
	e.lastUsed = now
	if e.cursor == nil {
		return e.content, nil, true
	}
	cur := *e.cursor
	return e.content, &cur, true
}

// put keeps a frame, dropping expired ones and, when full, the least
// recently used.
func (c *frameCache) put(key frameKey, content string, cursor *tea.Cursor) {
	now := c.now()
	c.gc(now)
	if _, ok := c.entries[key]; !ok && len(c.entries) >= frameCacheMaxEntries {
		var oldest frameKey
		var oldestEntry *frameEntry
		for k, e := range c.entries {
			if oldestEntry == nil || e.lastUsed.Before(oldestEntry.lastUsed) {
				oldest, oldestEntry = k, e
			}
		}
		delete(c.entries, oldest)
	}
	var cur *tea.Cursor
	if cursor != nil {
		copied := *cursor
		cur = &copied
	}
	c.entries[key] = &frameEntry{content: content, cursor: cur, at: now, lastUsed: now}
}

func (c *frameCache) gc(now time.Time) {
	for key, e := range c.entries {
		if c.expired(e, now) {
			delete(c.entries, key)
		}
	}
}

// markScrollOnly flags the current update as having changed nothing but the
// chat's scroll position or selection, which lets View serve a kept frame.
func (m *model) markScrollOnly() { m.frames.scrollOnly = true }

// frameCacheable reports whether the screen as it is now may be kept.
func (m *model) frameCacheable() bool {
	return !m.landing() && !m.dialogs.HasDialogs()
}

// endFrameUpdate arms the sweep after an update that only scrolled.
func (m *model) endFrameUpdate() tea.Cmd {
	if !m.frames.scrollOnly || !m.frameCacheable() || m.frames.gcArmed {
		return nil
	}
	m.frames.gcArmed = true
	return tea.Tick(frameCacheTTL, func(time.Time) tea.Msg { return frameGCMsg{} })
}

// handleFrameGC sweeps expired frames. The sweep changes nothing on screen,
// so it counts as scroll-only, which re-arms it while frames remain. Once
// the cache is empty the UI has been idle, and the next frame isn't kept.
func (m *model) handleFrameGC() {
	m.frames.gcArmed = false
	m.frames.gc(m.frames.now())
	if len(m.frames.entries) == 0 {
		m.frames.skipPut = true
		return
	}
	m.markScrollOnly()
}

func (m *model) frameKeyNow() (frameKey, bool) {
	if !m.frameCacheable() {
		return frameKey{}, false
	}
	return frameKey{width: m.width, height: m.height, chat: m.chat.frameState()}, true
}

// currentFrameKey empties the cache unless this update only scrolled, and
// returns the key of the frame about to be drawn.
func (m *model) currentFrameKey() (frameKey, bool) {
	scrollOnly := m.frames.scrollOnly
	m.frames.scrollOnly = false
	if !scrollOnly {
		clear(m.frames.entries)
	}
	return m.frameKeyNow()
}

// storeFrame keeps a frame just drawn under key, unless drawing changed what
// key describes: following the newest message scrolls the list, and
// rendering can update a message's version.
func (m *model) storeFrame(key frameKey, content string, cursor *tea.Cursor) {
	if m.frames.skipPut {
		m.frames.skipPut = false
		return
	}
	if now, ok := m.frameKeyNow(); !ok || now != key {
		return
	}
	m.frames.put(key, content, cursor)
}

// coalescedWheelMsg is one or more wheel events merged by inputFilter.
// DeltaY is in wheel steps, negative upwards.
type coalescedWheelMsg struct {
	Mouse          tea.Mouse
	DeltaX, DeltaY float64
}

// inputFilter merges bursts of mouse input before they reach Update, so a
// fast wheel spin can't queue up ahead of key presses. Use its filter method
// with tea.WithFilter.
type inputFilter struct {
	now func() time.Time

	lastMotion, lastWheel time.Time
	wheelDX, wheelDY      float64
}

func newInputFilter() *inputFilter { return &inputFilter{now: time.Now} }

func (f *inputFilter) filter(_ tea.Model, msg tea.Msg) tea.Msg {
	switch msg := msg.(type) {
	case tea.MouseWheelMsg:
		mouse := msg.Mouse()
		dx, dy := wheelDeltas(mouse)
		if oppositeSign(f.wheelDX, dx) {
			f.wheelDX = 0
		}
		if oppositeSign(f.wheelDY, dy) {
			f.wheelDY = 0
		}
		f.wheelDX += dx
		f.wheelDY += dy
		if !f.allow(&f.lastWheel) {
			return nil
		}
		merged := coalescedWheelMsg{Mouse: mouse, DeltaX: f.wheelDX, DeltaY: f.wheelDY}
		f.wheelDX, f.wheelDY = 0, 0
		return merged

	case tea.MouseMotionMsg:
		if !f.allow(&f.lastMotion) {
			return nil
		}
	}
	return msg
}

// allow reports whether enough time has passed since the last sample let
// through in a stream.
func (f *inputFilter) allow(last *time.Time) bool {
	at := f.now()
	if !last.IsZero() && at.Sub(*last) < inputFilterInterval {
		return false
	}
	*last = at
	return true
}

// wheelDeltas turns a wheel event, which Bubble Tea reports as a button,
// into one signed step.
func wheelDeltas(mouse tea.Mouse) (dx, dy float64) {
	switch mouse.Button {
	case tea.MouseWheelUp:
		return 0, -1
	case tea.MouseWheelDown:
		return 0, 1
	case tea.MouseWheelLeft:
		return -1, 0
	case tea.MouseWheelRight:
		return 1, 0
	}
	return 0, 0
}

func oppositeSign(a, b float64) bool {
	return (a < 0 && b > 0) || (a > 0 && b < 0)
}
