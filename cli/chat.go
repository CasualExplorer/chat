package main

// The chat list, its scrollbar and mouse selection follow Crush's
// (github.com/charmbracelet/crush, internal/ui/model/chat.go and
// internal/ui/common/scrollbar.go), Copyright 2025-2026 Charmbracelet, Inc.,
// used under FSL-1.1-MIT.

import (
	"strings"
	"time"
	"unicode"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"
	uv "github.com/charmbracelet/ultraviolet"
	"github.com/charmbracelet/x/ansi"

	"chat/list"
)

const (
	doubleClickThreshold = 400 * time.Millisecond
	clickTolerance       = 2 // cells the mouse may move between clicks of a double click
	scrollbarHideDelay   = time.Second
	wheelLines           = 3

	// After a width change the messages are re-rendered a batch at a time,
	// once resizing has settled, so the first scroll doesn't render them
	// all at once.
	prewarmDelay = 100 * time.Millisecond
	prewarmBatch = 8
)

// delayedClickMsg acts on a single click once it is clear it wasn't the
// start of a double click.
type delayedClickMsg struct {
	id, item, y int
}

// hideScrollbarMsg hides the scrollbar if nothing has scrolled since.
type hideScrollbarMsg struct{ seq int }

// prewarmMsg renders the next batch of messages at the current width,
// unless the width has changed again since.
type prewarmMsg struct{ seq, from int }

// chatView is the conversation: a lazily rendered list of messages that
// follows the newest one unless the user scrolls away.
type chatView struct {
	com    *common
	list   *list.List
	follow bool // keep the newest message in view

	scrollbarShown bool
	scrollbarSeq   int

	prewarmSeq int

	// Mouse selection, in list coordinates: the item and position where the
	// button went down, and where it has been dragged to.
	mouseDown                  bool
	downItem, downX, downY     int
	dragItem, dragX, dragY     int
	lastClick                  time.Time
	lastClickX, lastClickY     int
	clickCount, pendingClickID int
}

func newChatView(com *common) *chatView {
	l := list.NewList()
	l.SetGap(1)
	c := &chatView{com: com, list: l, follow: true, downItem: -1, dragItem: -1}
	l.RegisterRenderCallback(list.FocusedRenderCallback(l))
	l.RegisterRenderCallback(c.applyHighlight)
	return c
}

func (c *chatView) Len() int { return c.list.Len() }

// SetSize resizes the chat. A new width drops every rendered message, so it
// returns a command that renders them again in the background.
func (c *chatView) SetSize(width, height int) tea.Cmd {
	widthChanged := width != c.list.Width()
	c.list.SetSize(width, height)
	if !widthChanged || c.list.Len() == 0 {
		return nil
	}
	c.prewarmSeq++
	seq := c.prewarmSeq
	return tea.Tick(prewarmDelay, func(time.Time) tea.Msg { return prewarmMsg{seq: seq} })
}

// Prewarm renders a batch of messages and asks for the next one.
func (c *chatView) Prewarm(msg prewarmMsg) tea.Cmd {
	if msg.seq != c.prewarmSeq {
		return nil
	}
	next := c.list.Prewarm(msg.from, prewarmBatch)
	if next >= c.list.Len() {
		return nil
	}
	return func() tea.Msg { return prewarmMsg{seq: msg.seq, from: next} }
}

func (c *chatView) item(i int) chatItem {
	if it, ok := c.list.ItemAt(i).(chatItem); ok {
		return it
	}
	return nil
}

// lastAssistant returns the newest message if it is a reply.
func (c *chatView) lastAssistant() *assistantItem {
	a, _ := c.list.ItemAt(c.list.Len() - 1).(*assistantItem)
	return a
}

// lastReply returns the newest reply with text.
func (c *chatView) lastReply() *assistantItem {
	for i := c.list.Len() - 1; i >= 0; i-- {
		if a, ok := c.list.ItemAt(i).(*assistantItem); ok && a.text != "" {
			return a
		}
	}
	return nil
}

func (c *chatView) Append(items ...chatItem) {
	for _, it := range items {
		c.list.AppendItems(it)
	}
}

func (c *chatView) Clear() {
	c.ClearHighlight()
	c.list.SetItems()
	c.list.SetSelected(-1)
	c.follow = true
}

// Focus lets the keyboard move through the messages, starting from the
// newest one in view.
func (c *chatView) Focus() {
	c.list.Focus()
	if c.list.Selected() < 0 {
		c.list.SelectLastInView()
	}
}

func (c *chatView) Blur()         { c.list.Blur() }
func (c *chatView) Focused() bool { return c.list.Focused() }

// Draw paints the visible messages into area and, while scrolling, a
// scrollbar in the column just right of it.
func (c *chatView) Draw(scr uv.Screen, area uv.Rectangle) {
	if c.follow {
		c.list.ScrollToBottom()
	}
	uv.NewStyledString(c.list.Render()).Draw(scr, area)

	height := area.Dy()
	if !c.scrollbarShown || !c.list.Overflows(height) {
		return
	}
	bar := scrollbar(c.com.styles, height, c.list.TotalHeight(), height, c.list.Offset())
	uv.NewStyledString(bar).Draw(scr, uv.Rect(area.Max.X, area.Min.Y, 1, height))
}

// scrollbar draws a vertical scrollbar, or nothing if the content fits.
func scrollbar(st *Styles, height, contentSize, viewportSize, offset int) string {
	if height <= 0 || contentSize <= viewportSize {
		return ""
	}
	thumbSize := max(1, height*viewportSize/contentSize)
	maxOffset := contentSize - viewportSize
	trackSpace := height - thumbSize
	thumbPos := 0
	if trackSpace > 0 && maxOffset > 0 {
		thumbPos = min(trackSpace, offset*trackSpace/maxOffset)
	}
	var b strings.Builder
	for i := range height {
		if i > 0 {
			b.WriteString("\n")
		}
		if i >= thumbPos && i < thumbPos+thumbSize {
			b.WriteString(st.ScrollbarThumb.Render(scrollbarThumb))
		} else {
			b.WriteString(st.ScrollbarTrack.Render(scrollbarTrack))
		}
	}
	return b.String()
}

// scrolled shows the scrollbar for a moment and works out whether the view
// is still following the newest message.
func (c *chatView) scrolled() tea.Cmd {
	c.follow = c.list.AtBottom()
	c.scrollbarShown = true
	c.scrollbarSeq++
	seq := c.scrollbarSeq
	return tea.Tick(scrollbarHideDelay, func(time.Time) tea.Msg { return hideScrollbarMsg{seq} })
}

func (c *chatView) HideScrollbar(msg hideScrollbarMsg) {
	if msg.seq == c.scrollbarSeq {
		c.scrollbarShown = false
	}
}

func (c *chatView) ScrollBy(lines int) tea.Cmd {
	if c.follow {
		c.list.ScrollToBottom() // Draw may not have pinned it yet
	}
	c.list.ScrollBy(lines)
	return c.scrolled()
}

// ScrollLines scrolls n lines, as ↑/↓ do. If the selected message leaves
// the view, the selection moves to the nearest message still in it.
func (c *chatView) ScrollLines(n int) tea.Cmd {
	cmd := c.ScrollBy(n)
	if c.list.Selected() >= 0 && !c.list.SelectedItemInView() {
		if start, _ := c.list.VisibleItemIndices(); c.list.Selected() < start {
			c.list.SelectFirstInView()
		} else {
			c.list.SelectLastInView()
		}
	}
	return cmd
}

// The page keys scroll and select the message at the edge they scrolled
// towards.
func (c *chatView) PageUp() tea.Cmd       { return c.scrollPage(-c.list.Height()) }
func (c *chatView) PageDown() tea.Cmd     { return c.scrollPage(c.list.Height()) }
func (c *chatView) HalfPageUp() tea.Cmd   { return c.scrollPage(-c.list.Height() / 2) }
func (c *chatView) HalfPageDown() tea.Cmd { return c.scrollPage(c.list.Height() / 2) }

func (c *chatView) scrollPage(lines int) tea.Cmd {
	cmd := c.ScrollBy(lines)
	if lines < 0 {
		c.list.SelectFirstInView()
	} else {
		c.list.SelectLastInView()
	}
	return cmd
}

// frameState is everything about the chat that a drawn frame depends on
// besides the messages' versions, for the frame cache.
type frameState struct {
	offsetIdx, offsetLine int
	selected              int
	focused, scrollbar    bool
	items                 uint64
}

func (c *chatView) frameState() frameState {
	idx, line := c.list.ScrollPosition()
	return frameState{
		offsetIdx:  idx,
		offsetLine: line,
		selected:   c.list.Selected(),
		focused:    c.list.Focused(),
		scrollbar:  c.scrollbarShown,
		items:      c.list.ItemsVersion(),
	}
}

func (c *chatView) ScrollToTop() tea.Cmd {
	c.list.ScrollToTop()
	c.list.SelectFirst()
	return c.scrolled()
}

func (c *chatView) ScrollToBottom() tea.Cmd {
	c.list.ScrollToBottom()
	c.list.SelectLast()
	return c.scrolled()
}

// SelectPrev moves the selection to the previous message, scrolling it
// into view.
func (c *chatView) SelectPrev() tea.Cmd {
	if c.follow {
		c.list.ScrollToBottom()
	}
	if c.list.Selected() < 0 || !c.list.SelectedItemInView() {
		c.list.SelectLastInView()
	} else {
		c.list.SelectPrev()
	}
	c.list.ScrollToSelected()
	return c.scrolled()
}

// SelectNext moves the selection to the next message, scrolling it into
// view.
func (c *chatView) SelectNext() tea.Cmd {
	if c.follow {
		c.list.ScrollToBottom()
	}
	if c.list.Selected() < 0 || !c.list.SelectedItemInView() {
		c.list.SelectFirstInView()
	} else {
		c.list.SelectNext()
	}
	c.list.ScrollToSelected()
	if c.list.IsSelectedLast() {
		// Show all of the newest message, not just its top.
		c.list.ScrollToBottom()
	}
	return c.scrolled()
}

// ToggleExpandedSelected expands or collapses the selected reply's
// thinking summary.
func (c *chatView) ToggleExpandedSelected() bool {
	a, ok := c.list.SelectedItem().(*assistantItem)
	return ok && a.ToggleExpanded()
}

// SelectedText is the markdown source of the selected message.
func (c *chatView) SelectedText() string {
	if it, ok := c.list.SelectedItem().(chatItem); ok {
		return it.Text()
	}
	return ""
}

// HandleMouseDown starts a selection at (x, y) in the chat. A double click
// selects a word and a triple click a line; a single click that doesn't
// turn into either is acted on later through delayedClickMsg.
func (c *chatView) HandleMouseDown(x, y int) (bool, tea.Cmd) {
	itemIdx, itemY := c.list.ItemIndexAtPosition(x, y)
	if itemIdx < 0 {
		return false, nil
	}

	c.pendingClickID++
	now := time.Now()
	if now.Sub(c.lastClick) <= doubleClickThreshold && abs(x-c.lastClickX) <= clickTolerance && abs(y-c.lastClickY) <= clickTolerance {
		c.clickCount++
	} else {
		c.clickCount = 1
	}
	c.lastClick, c.lastClickX, c.lastClickY = now, x, y
	c.list.SetSelected(itemIdx)
	c.mouseDown = true

	switch c.clickCount {
	case 1:
		c.setSelection(itemIdx, x, itemY, x, itemY)
		id := c.pendingClickID
		return true, tea.Tick(doubleClickThreshold, func(time.Time) tea.Msg {
			return delayedClickMsg{id: id, item: itemIdx, y: itemY}
		})
	case 2:
		c.selectWord(itemIdx, x, itemY)
	default:
		c.selectLine(itemIdx, itemY)
		c.clickCount = 0
	}
	return true, nil
}

// HandleMouseDrag extends the selection to (x, y), which is clamped to the
// chat so dragging past its edge selects to the edge.
func (c *chatView) HandleMouseDrag(x, y int) bool {
	if !c.mouseDown {
		return false
	}
	x = max(0, min(x, c.list.Width()))
	y = max(0, min(y, c.list.Height()-1))
	itemIdx, itemY := c.list.ItemIndexAtPosition(x, y)
	if itemIdx < 0 {
		return false
	}
	c.dragItem, c.dragX, c.dragY = itemIdx, x, itemY
	return true
}

// HandleMouseUp ends a selection and returns the selected text, if any.
func (c *chatView) HandleMouseUp() (string, bool) {
	if !c.mouseDown {
		return "", false
	}
	c.mouseDown = false
	return c.HighlightContent(), true
}

// HandleDelayedClick toggles a reply's thinking summary when it was clicked
// once, without dragging out a selection.
func (c *chatView) HandleDelayedClick(msg delayedClickMsg) bool {
	if msg.id != c.pendingClickID || c.HasHighlight() {
		return false
	}
	a, ok := c.list.ItemAt(msg.item).(*assistantItem)
	return ok && a.HandleMouseClick(msg.y) && a.ToggleExpanded()
}

func (c *chatView) setSelection(downItem, downX, downY, dragX, dragY int) {
	c.downItem, c.downX, c.downY = downItem, downX, downY
	c.dragItem, c.dragX, c.dragY = downItem, dragX, dragY
}

func (c *chatView) ClearHighlight() {
	c.mouseDown = false
	c.downItem, c.dragItem = -1, -1
	c.clickCount = 0
	c.pendingClickID++
}

func (c *chatView) HasHighlight() bool {
	startItem, startLine, startCol, endItem, endLine, endCol := c.highlightRange()
	return startItem >= 0 && endItem >= 0 && (startItem != endItem || startLine != endLine || startCol != endCol)
}

// HighlightContent returns the text selected with the mouse.
func (c *chatView) HighlightContent() string {
	if !c.HasHighlight() {
		return ""
	}
	startItem, _, _, endItem, _, _ := c.highlightRange()
	width := c.list.Width()
	var b strings.Builder
	for i := startItem; i <= endItem; i++ {
		it, ok := c.list.ItemAt(i).(chatItem)
		if !ok {
			continue
		}
		// The highlight is set on each item as it renders; set it here too
		// in case the item hasn't been drawn since the selection changed.
		c.applyHighlight(i, c.list.Selected(), it)
		startLine, startCol, endLine, endCol := it.Highlight()
		rendered := it.RawRender(width)
		b.WriteString(list.HighlightContent(rendered, uv.Rect(0, 0, lipgloss.Width(rendered), lipgloss.Height(rendered)),
			startLine, startCol, endLine, endCol))
		b.WriteString("\n")
	}
	return strings.TrimSpace(b.String())
}

// applyHighlight is a list render callback that gives each item its part
// of the mouse selection.
func (c *chatView) applyHighlight(idx, _ int, item list.Item) list.Item {
	hi, ok := item.(list.Highlightable)
	if !ok {
		return item
	}
	startItem, startLine, startCol, endItem, endLine, endCol := c.highlightRange()
	sLine, sCol, eLine, eCol := -1, -1, -1, -1
	if startItem >= 0 && idx >= startItem && idx <= endItem {
		sLine, sCol, eLine, eCol = 0, 0, -1, -1
		if idx == startItem {
			sLine, sCol = startLine, startCol
		}
		if idx == endItem {
			eLine, eCol = endLine, endCol
		}
	}
	hi.SetHighlight(sLine, sCol, eLine, eCol)
	return item
}

// highlightRange orders the selection's two ends.
func (c *chatView) highlightRange() (startItem, startLine, startCol, endItem, endLine, endCol int) {
	if c.downItem < 0 || c.dragItem < 0 {
		return -1, -1, -1, -1, -1, -1
	}
	forward := c.dragItem > c.downItem ||
		(c.dragItem == c.downItem && c.dragY > c.downY) ||
		(c.dragItem == c.downItem && c.dragY == c.downY && c.dragX >= c.downX)
	if forward {
		return c.downItem, c.downY, c.downX, c.dragItem, c.dragY, c.dragX
	}
	return c.dragItem, c.dragY, c.dragX, c.downItem, c.downY, c.downX
}

// selectWord selects the word under x on row itemY of an item.
func (c *chatView) selectWord(itemIdx, x, itemY int) {
	it, ok := c.list.ItemAt(itemIdx).(chatItem)
	if !ok {
		return
	}
	lines := strings.Split(it.RawRender(c.list.Width()), "\n")
	if itemY < 0 || itemY >= len(lines) {
		return
	}
	start, end := wordBounds(ansi.Strip(lines[itemY]), max(x-prefixWidth, 0))
	if start == end {
		c.setSelection(itemIdx, x, itemY, x, itemY)
		return
	}
	c.setSelection(itemIdx, start+prefixWidth, itemY, end+prefixWidth, itemY)
}

// selectLine selects row itemY of an item.
func (c *chatView) selectLine(itemIdx, itemY int) {
	it, ok := c.list.ItemAt(itemIdx).(chatItem)
	if !ok {
		return
	}
	lines := strings.Split(it.RawRender(c.list.Width()), "\n")
	if itemY < 0 || itemY >= len(lines) {
		return
	}
	c.setSelection(itemIdx, 0, itemY, ansi.StringWidth(lines[itemY])+prefixWidth, itemY)
}

// wordBounds returns the columns [start, end) of the run of non-space
// characters at column col of line, or an empty range on a space.
func wordBounds(line string, col int) (start, end int) {
	type cell struct {
		r          rune
		start, end int
	}
	var cells []cell
	x := 0
	for _, r := range line {
		w := max(ansi.StringWidth(string(r)), 1)
		cells = append(cells, cell{r, x, x + w})
		x += w
	}
	at := -1
	for i, c := range cells {
		if col >= c.start && col < c.end {
			at = i
			break
		}
	}
	if at < 0 || unicode.IsSpace(cells[at].r) {
		return col, col
	}
	first, last := at, at
	for first > 0 && !unicode.IsSpace(cells[first-1].r) {
		first--
	}
	for last < len(cells)-1 && !unicode.IsSpace(cells[last+1].r) {
		last++
	}
	return cells[first].start, cells[last].end
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
