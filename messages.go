package main

// The message items follow Crush's (github.com/charmbracelet/crush,
// internal/ui/chat), Copyright 2025-2026 Charmbracelet, Inc., used under
// FSL-1.1-MIT.

import (
	"fmt"
	"image"
	"strings"
	"time"

	"charm.land/glamour/v2"
	"charm.land/lipgloss/v2"
	xansi "github.com/charmbracelet/x/ansi"

	"chat/list"
)

const (
	maxTextWidth = 120 // message text is capped here for readability
	prefixWidth  = 2   // a message's left border or padding

	// A thinking summary longer than this shows only its last lines until
	// it is expanded.
	maxCollapsedThinkingHeight = 10
	thinkingHiddenFormat       = "… (%d lines hidden) [space or click to expand]"
)

// chatItem is one message in the conversation list.
type chatItem interface {
	list.Item
	list.Focusable
	list.Highlightable
	// RawRender renders the message without its left border or padding,
	// which is what a mouse selection is measured against.
	RawRender(width int) string
	// Text is the message's markdown source, which "copy" puts on the
	// clipboard.
	Text() string
}

// contentWidth is the width message text is wrapped to inside a list of the
// given width.
func contentWidth(width int) int {
	return max(min(width-prefixWidth, maxTextWidth), 20)
}

// messageItem is what every message has: a version for the list's render
// cache, whether it is selected, and the range highlighted with the mouse.
type messageItem struct {
	*list.Versioned
	com     *common
	focused bool

	// The highlight, in content coordinates (right of the prefix). A start
	// line of -1 means nothing is highlighted; an end of -1 means the end of
	// the message.
	hlStartLine, hlStartCol, hlEndLine, hlEndCol int
}

func newMessageItem(com *common) messageItem {
	return messageItem{Versioned: list.NewVersioned(), com: com, hlStartLine: -1, hlStartCol: -1, hlEndLine: -1, hlEndCol: -1}
}

func (m *messageItem) SetFocused(focused bool) {
	if m.focused != focused {
		m.focused = focused
		m.Bump()
	}
}

// SetHighlight takes columns in list coordinates, which include the
// message's prefix, and stores them relative to the content.
func (m *messageItem) SetHighlight(startLine, startCol, endLine, endCol int) {
	if startCol >= 0 {
		startCol = max(0, startCol-prefixWidth)
	}
	if endCol >= 0 {
		endCol = max(0, endCol-prefixWidth)
	}
	if m.hlStartLine == startLine && m.hlStartCol == startCol && m.hlEndLine == endLine && m.hlEndCol == endCol {
		return
	}
	m.hlStartLine, m.hlStartCol, m.hlEndLine, m.hlEndCol = startLine, startCol, endLine, endCol
	m.Bump()
}

func (m *messageItem) Highlight() (startLine, startCol, endLine, endCol int) {
	return m.hlStartLine, m.hlStartCol, m.hlEndLine, m.hlEndCol
}

// decorate highlights the selected range of content and puts the message's
// border or padding in front of every line.
func (m *messageItem) decorate(content string, blurred, focused lipgloss.Style) string {
	if m.hlStartLine >= 0 {
		area := image.Rect(0, 0, lipgloss.Width(content), lipgloss.Height(content))
		content = list.Highlight(content, area, m.hlStartLine, m.hlStartCol, m.hlEndLine, m.hlEndCol,
			list.ToHighlighter(m.com.styles.TextSelection))
	}
	prefix := blurred
	if m.focused {
		prefix = focused
	}
	return prefixLines(content, prefix.Render())
}

// userItem is a message the user sent, drawn behind a coloured left border.
type userItem struct {
	messageItem
	text string
}

func newUserItem(com *common, text string) *userItem {
	return &userItem{messageItem: newMessageItem(com), text: text}
}

func (u *userItem) Text() string { return u.text }

func (u *userItem) RawRender(width int) string {
	w := contentWidth(width)
	return renderMarkdown(u.com.styles, u.com.renderer(rendererUser, w), u.text, w)
}

func (u *userItem) Render(width int) string {
	st := u.com.styles
	return u.decorate(u.RawRender(width), st.Messages.UserBlurred, st.Messages.UserFocused)
}

// assistantItem is a reply: the reasoning summary in a muted box, the reply,
// any note, and a footer naming the model. Until the reply's text starts it
// shows the spinner.
type assistantItem struct {
	messageItem
	provider string // who is replying
	model    string
	text     string
	thinking string // reasoning summary, streamed before the reply
	note     string
	usage    *Usage // once the reply completes, if the provider reported it
	pending  bool   // the reply hasn't completed yet
	canceled bool   // the reply was stopped; any text that arrived is kept
	failure  string // why the reply failed; any text that arrived is kept

	start      time.Time     // when the request was sent
	elapsed    time.Duration // set once the reply completes
	thinkStart time.Time     // first thinking event
	thinkFor   time.Duration // set once the reply text starts

	spinner  *spinner
	expanded bool // the whole thinking summary is shown

	// Streamed text is re-rendered on every change; these keep the rendered
	// stable prefix so only the paragraph still arriving is redone.
	streamText, streamThinking streamingMarkdown

	// The thinking section is cached separately, so streaming the reply
	// doesn't redo it.
	thinkingCache    string
	thinkingCacheKey thinkingKey
	thinkingLines    int // rendered lines of the summary, before collapsing
	thinkingHeight   int // rows of the thinking box, for clicks
}

type thinkingKey struct {
	width, length int
	expanded      bool
	took          time.Duration
}

func newAssistantItem(com *common, provider, model string) *assistantItem {
	return &assistantItem{
		messageItem: newMessageItem(com),
		provider:    provider,
		model:       model,
		pending:     true,
		start:       time.Now(),
		spinner:     newSpinner(com.styles),
	}
}

func (a *assistantItem) Text() string { return a.text }

// spinning reports whether the reply shows the spinner, which it does until
// its text starts.
func (a *assistantItem) spinning() bool {
	return a.pending && strings.TrimSpace(a.text) == ""
}

func (a *assistantItem) appendText(delta string) {
	a.endThinking()
	a.text += delta
	a.Bump()
}

func (a *assistantItem) appendThinking(delta string) {
	if a.thinkStart.IsZero() {
		a.thinkStart = time.Now()
	}
	a.thinking += delta
	a.Bump()
}

// complete records the finished reply.
func (a *assistantItem) complete(text, note string, usage *Usage) {
	a.endThinking()
	a.elapsed = time.Since(a.start)
	a.text = text
	a.note = note
	a.usage = usage
	a.pending = false
	// From here on the reply is rendered whole (see renderText), so the
	// streaming caches can go.
	a.streamText.Reset()
	a.streamThinking.Reset()
	a.thinkingCache = ""
	a.Bump()
}

// fail ends a reply that didn't complete, keeping whatever text arrived.
// The reply shows "Canceled" if it was stopped, or else an error banner.
func (a *assistantItem) fail(reason string, canceled bool) {
	a.endThinking()
	a.elapsed = time.Since(a.start)
	a.pending = false
	if canceled {
		a.canceled = true
	} else {
		a.failure = reason
	}
	a.streamText.Reset()
	a.streamThinking.Reset()
	a.thinkingCache = ""
	a.Bump()
}

// failed reports whether the reply ended without completing.
func (a *assistantItem) failed() bool { return a.canceled || a.failure != "" }

// advance moves the spinner on a frame.
func (a *assistantItem) advance() {
	if a.spinning() {
		a.spinner.advance()
		a.Bump()
	}
}

// endThinking records how long the model thought, once its reply begins.
func (a *assistantItem) endThinking() {
	if !a.thinkStart.IsZero() && a.thinkFor == 0 {
		a.thinkFor = time.Since(a.thinkStart)
	}
}

// expandable reports whether the thinking summary has lines to show or hide.
func (a *assistantItem) expandable() bool {
	return a.expanded || a.thinkingLines > maxCollapsedThinkingHeight+1
}

// ToggleExpanded shows or hides the start of a long thinking summary. It
// reports whether there was anything to toggle.
func (a *assistantItem) ToggleExpanded() bool {
	if !a.expandable() {
		return false
	}
	a.expanded = !a.expanded
	a.Bump()
	return true
}

// HandleMouseClick reports whether a click at row y of the message landed
// on its thinking box, which toggles it.
func (a *assistantItem) HandleMouseClick(y int) bool {
	return y >= 0 && y < a.thinkingHeight && a.expandable()
}

func (a *assistantItem) RawRender(width int) string {
	st := a.com.styles
	w := contentWidth(width)

	var parts []string
	if strings.TrimSpace(a.thinking) != "" {
		parts = append(parts, a.renderThinking(w))
	} else {
		a.thinkingHeight = 0
	}

	switch {
	case a.spinning():
		label := ""
		if a.thinking != "" {
			label = "Thinking"
		}
		parts = append(parts, a.spinner.view(label, time.Since(a.start)))
	case a.text != "":
		parts = append(parts, a.renderText(&a.streamText, rendererReply, a.text, w))
	case !a.failed():
		parts = append(parts, st.Messages.Canceled.Render("(no text in this reply)"))
	}

	switch {
	case a.canceled:
		parts = append(parts, st.Messages.Canceled.Render("Canceled"))
	case a.failure != "":
		parts = append(parts, errorBanner(st, a.failure, w))
	case a.note != "":
		parts = append(parts, st.Messages.Canceled.Render(a.note))
	}
	if !a.pending && !a.failed() {
		parts = append(parts, infoLine(st, a, w))
	}
	return strings.Join(parts, "\n\n")
}

func (a *assistantItem) Render(width int) string {
	st := a.com.styles
	return a.decorate(a.RawRender(width), st.Messages.AssistantBlurred, st.Messages.AssistantFocused)
}

// renderThinking draws the reasoning summary as a muted box showing its last
// lines unless expanded, with a "Thought for" footer once the reply starts.
func (a *assistantItem) renderThinking(width int) string {
	key := thinkingKey{width: width, length: len(a.thinking), expanded: a.expanded, took: a.thinkFor}
	if a.thinkingCache != "" && a.thinkingCacheKey == key {
		return a.thinkingCache
	}

	st := a.com.styles
	rendered := a.renderText(&a.streamThinking, rendererQuiet, strings.TrimSpace(a.thinking), width)
	a.thinkingLines = countLines(rendered)
	if !a.expanded && a.thinkingLines > maxCollapsedThinkingHeight+1 {
		tail, hidden := tailLines(rendered, maxCollapsedThinkingHeight, a.thinkingLines)
		rendered = st.Messages.ThinkingHint.Render(fmt.Sprintf(thinkingHiddenFormat, hidden)) + "\n\n" + trimBlankLines(tail)
	}
	out := st.Messages.ThinkingBox.Width(width).Render(rendered)
	a.thinkingHeight = lipgloss.Height(out)

	if took := a.thinkFor.Round(100 * time.Millisecond); took > 0 {
		out += "\n\n" + st.Messages.ThinkingFooterTitle.Render("Thought for ") +
			st.Messages.ThinkingFooterDuration.Render(took.String())
	}
	a.thinkingCache, a.thinkingCacheKey = out, key
	return out
}

// renderText renders markdown that is still streaming from the cached
// stable prefix, and a finished reply whole: the streamed render is glued
// from pieces, which can wrap differently from the whole.
func (a *assistantItem) renderText(cache *streamingMarkdown, kind rendererKind, text string, width int) string {
	r := a.com.renderer(kind, width)
	if a.pending {
		return renderStreamingMarkdown(a.com.styles, cache, r, text, width)
	}
	return renderMarkdown(a.com.styles, r, text, width)
}

// errorBanner is a failed reply's "ERROR why it failed", with the reason
// wrapped beside the tag.
func errorBanner(st *Styles, reason string, width int) string {
	tag := st.Messages.ErrorTag.Render()
	textWidth := max(width-lipgloss.Width(tag)-1, 10)
	return lipgloss.JoinHorizontal(lipgloss.Top, tag, " ", st.Messages.ErrorTitle.Width(textWidth).Render(reason))
}

// infoLine is the footer under a finished reply:
// "◇ model via Provider in 2.3s · 12.4K in · 845 out ──────".
func infoLine(st *Styles, a *assistantItem, width int) string {
	info := "via " + a.provider + " in " + a.elapsed.Round(100*time.Millisecond).String()
	if a.usage != nil {
		info += " · " + formatTokens(a.usage.Input) + " in · " + formatTokens(a.usage.Output) + " out"
	}
	return section(st, st.Subtle.Render(modelIcon)+" "+st.Muted.Render(a.model)+" "+st.Subtle.Render(info), width)
}

// formatTokens shortens a token count: 845, 12.4K, 1.2M.
func formatTokens(n int64) string {
	var s string
	switch {
	case n >= 1_000_000:
		s = fmt.Sprintf("%.1fM", float64(n)/1_000_000)
	case n >= 1_000:
		s = fmt.Sprintf("%.1fK", float64(n)/1_000)
	default:
		return fmt.Sprint(n)
	}
	return strings.Replace(s, ".0", "", 1)
}

func renderMarkdown(st *Styles, r *glamour.TermRenderer, text string, width int) string {
	if r != nil {
		if out, err := r.Render(text); err == nil {
			return trimBlankLines(out)
		}
	}
	return st.Base.Width(width).Render(text)
}

// renderStreamingMarkdown renders text that may still be growing, reusing
// the rendered stable prefix in cache.
func renderStreamingMarkdown(st *Styles, cache *streamingMarkdown, r *glamour.TermRenderer, text string, width int) string {
	if r == nil {
		return st.Base.Width(width).Render(text)
	}
	return trimBlankLines(cache.Render(text, width, r))
}

// trimBlankLines drops the leading and trailing lines glamour pads its
// output with, which hold styled spaces rather than being empty.
func trimBlankLines(s string) string {
	lines := strings.Split(s, "\n")
	isBlank := func(line string) bool { return strings.TrimSpace(xansi.Strip(line)) == "" }
	for len(lines) > 0 && isBlank(lines[0]) {
		lines = lines[1:]
	}
	for len(lines) > 0 && isBlank(lines[len(lines)-1]) {
		lines = lines[:len(lines)-1]
	}
	return strings.Join(lines, "\n")
}

// prefixLines puts a message's border or padding in front of every line.
func prefixLines(s, prefix string) string {
	lines := strings.Split(s, "\n")
	for i, line := range lines {
		lines[i] = prefix + line
	}
	return strings.Join(lines, "\n")
}
