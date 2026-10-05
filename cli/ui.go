package main

import (
	"context"
	"errors"
	"image"
	"os"
	"slices"
	"strings"
	"time"

	"charm.land/bubbles/v2/help"
	"charm.land/bubbles/v2/key"
	"charm.land/bubbles/v2/textarea"
	tea "charm.land/bubbletea/v2"
	"github.com/atotto/clipboard"
	uv "github.com/charmbracelet/ultraviolet"
)

const (
	inputMinLines  = 3
	inputMaxLines  = 15
	renderInterval = spinnerInterval  // also the spinner's frame rate
	infoTimeout    = 5 * time.Second  // how long an info message stays up
	modelsTimeout  = 15 * time.Second // for listing a provider's models
	cancelTimeout  = 2 * time.Second  // how long a first Esc waits for a second
)

// focusState is where the keys go when no dialog is open.
type focusState int

const (
	focusEditor focusState = iota
	focusChat
)

type statusKind int

const (
	statusError statusKind = iota
	statusWarn
	statusInfo
)

type streamEventMsg struct {
	event StreamEvent
	ok    bool // false once the provider has closed the channel
}

type renderTickMsg struct{}

// clearStatusMsg clears an info message, unless another replaced it.
type clearStatusMsg struct{ seq int }

// cancelTimerExpiredMsg ends the wait for a second Esc, unless another
// Esc started a new wait.
type cancelTimerExpiredMsg struct{ seq int }

// pasteFailedMsg reports that the clipboard held no text to paste.
type pasteFailedMsg struct{}

// modelsMsg carries the models a provider listed.
type modelsMsg struct {
	provider int
	models   []ModelInfo
	err      error
}

type model struct {
	com  *common
	keys keyMap

	providers []Provider
	active    int
	effort    []string      // each provider's chosen reasoning effort
	catalog   [][]ModelInfo // each provider's models, once listed
	usage     *Usage        // the last reply's, for the context shown

	chat     *chatView
	input    textarea.Model
	help     help.Model
	fullHelp bool
	dialogs  *overlay
	focus    focusState
	prompts  promptHistory

	inputSelecting bool // a mouse drag in the input box is selecting its text

	width  int
	height int
	rects  uiLayout

	cwd       string
	gitBranch string

	history []Turn

	streaming  bool
	events     <-chan StreamEvent
	cancel     context.CancelFunc
	canceling  bool // Esc was pressed once; a second press stops the reply
	cancelSeq  int
	sentInput  string // restored into the input box if the request fails
	status     string // last error, warning or info, shown over the help line
	statusKind statusKind
	statusSeq  int

	frames *frameCache
}

func newModel(providers []Provider, active int) model {
	com := newCommon(newStyles(pantera()))
	keys := defaultKeyMap()

	input := textarea.New()
	input.Placeholder = "Message…"
	input.SetPromptFunc(4, editorPrompt(com.styles))
	input.ShowLineNumbers = false
	input.DynamicHeight = true
	input.MinHeight = inputMinLines
	input.MaxHeight = inputMaxLines
	input.SetVirtualCursor(false) // View places the terminal's own cursor
	// New lines go through handleEditorKey: the textarea's own newline
	// stops at MaxHeight lines, which is meant only to cap the box's height.
	input.KeyMap.InsertNewline = key.NewBinding()
	input.KeyMap.SelectAll = keys.Editor.SelectAll
	// Copying a selection goes through m.copy, like every other copy.
	input.KeyMap.CopySelection = key.NewBinding()
	input.SetStyles(com.styles.Textarea)
	input.Focus()

	h := help.New()
	h.Styles = com.styles.Help

	cwd, _ := os.Getwd()
	return model{
		com:       com,
		keys:      keys,
		providers: providers,
		active:    active,
		effort:    make([]string, len(providers)),
		catalog:   make([][]ModelInfo, len(providers)),
		chat:      newChatView(com),
		input:     input,
		help:      h,
		dialogs:   &overlay{},
		prompts:   promptHistory{index: -1},
		cwd:       cwd,
		frames:    newFrameCache(),
	}
}

func (m model) Init() tea.Cmd {
	cmds := []tea.Cmd{fetchGitBranch}
	for i, p := range m.providers {
		if l, ok := p.(modelLister); ok {
			cmds = append(cmds, listModels(i, l))
		}
	}
	return tea.Batch(cmds...)
}

// listModels asks a provider for its models. Failures are not shown: the
// model picker falls back to the models it knows.
func listModels(provider int, l modelLister) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
		defer cancel()
		models, err := l.ListModels(ctx)
		return modelsMsg{provider: provider, models: models, err: err}
	}
}

// Update handles msg. Updates that only scroll the chat let View reuse a
// frame it drew before (see framecache.go).
func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	m.frames.scrollOnly = false
	m.frames.skipPut = false
	next, cmd := m.update(msg)
	m = next.(model)
	return m, tea.Batch(cmd, m.endFrameUpdate())
}

func (m model) update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
		return m, m.layout()

	case gitBranchMsg:
		m.gitBranch = string(msg)
		return m, nil

	case modelsMsg:
		if msg.err == nil {
			m.catalog[msg.provider] = msg.models
		}
		return m, nil

	case streamEventMsg:
		return m.handleStreamEvent(msg)

	case renderTickMsg:
		// Only the spinner changes, which bumps its message's version.
		m.markScrollOnly()
		if !m.streaming {
			return m, nil
		}
		if last := m.chat.lastAssistant(); last != nil {
			last.advance()
		}
		return m, renderTick()

	case prewarmMsg:
		return m, m.chat.Prewarm(msg)

	case hideScrollbarMsg:
		m.markScrollOnly()
		m.chat.HideScrollbar(msg)
		return m, nil

	case frameGCMsg:
		m.handleFrameGC()
		return m, nil

	case cancelTimerExpiredMsg:
		if msg.seq == m.cancelSeq {
			m.canceling = false
		}
		return m, nil

	case pasteFailedMsg:
		return m, m.setStatus(statusWarn, "The clipboard is empty or holds no text.")

	case clearStatusMsg:
		if msg.seq == m.statusSeq && m.statusKind == statusInfo {
			m.status = ""
		}
		return m, nil

	case delayedClickMsg:
		m.chat.HandleDelayedClick(msg)
		return m, nil

	case editorDoneMsg:
		if msg.err != nil {
			return m, m.setStatus(statusError, "Editor: "+msg.err.Error())
		}
		m.setInput(msg.text)
		return m, nil

	case coalescedWheelMsg:
		lines := int(msg.DeltaY) * wheelLines
		if m.dialogs.HasDialogs() || m.landing() || lines == 0 || !image.Pt(msg.Mouse.X, msg.Mouse.Y).In(m.rects.main) {
			return m, nil
		}
		m.markScrollOnly()
		return m, m.chat.ScrollBy(lines)

	case tea.MouseClickMsg:
		return m.handleMouseClick(msg)

	case tea.MouseMotionMsg:
		if m.dialogs.HasDialogs() {
			return m, nil
		}
		// A drag that started in the input box selects its text, even
		// when the pointer leaves it.
		if m.inputSelecting {
			at := image.Pt(msg.X, msg.Y).Sub(m.inputArea().Min)
			m.input.ExtendSelection(at.X, at.Y)
			return m, nil
		}
		m.chat.HandleMouseDrag(msg.X-m.rects.main.Min.X, msg.Y-m.rects.main.Min.Y)
		return m, nil

	case tea.MouseReleaseMsg:
		if m.inputSelecting {
			m.inputSelecting = false
			m.input.EndSelection()
			return m, nil
		}
		if text, ok := m.chat.HandleMouseUp(); ok && text != "" {
			return m, m.copy(text)
		}
		return m, nil

	case tea.KeyPressMsg:
		if m.dialogs.HasDialogs() {
			return m.handleAction(m.dialogs.Update(msg))
		}
		return m.handleKey(msg)

	case tea.PasteMsg:
		// The textarea would turn each \r\n into two line breaks, and
		// Windows clipboard text uses \r\n.
		msg.Content = strings.ReplaceAll(msg.Content, "\r\n", "\n")
		if m.dialogs.HasDialogs() {
			return m.handleAction(m.dialogs.Update(msg))
		}
		return m, m.updateInput(msg)
	}

	return m, m.updateInput(msg)
}

func (m model) handleKey(msg tea.KeyPressMsg) (tea.Model, tea.Cmd) {
	k := m.keys
	switch {
	case key.Matches(msg, k.Quit):
		m.dialogs.Open(newQuitDialog(m.com))
		return m, nil
	case key.Matches(msg, k.Commands):
		m.dialogs.Open(newCommandsDialog(m.com, m.keys))
		return m, nil
	case key.Matches(msg, k.Models):
		m.dialogs.Open(newModelsDialog(m.com, m.providers, m.catalog, m.active))
		return m, nil
	case key.Matches(msg, k.Help):
		m.fullHelp = !m.fullHelp
		m.layout()
		return m, nil
	case key.Matches(msg, k.NewChat):
		return m, m.newChat()
	case key.Matches(msg, k.Tab):
		if m.focus == focusEditor && !m.landing() {
			m.setFocus(focusChat)
		} else {
			m.setFocus(focusEditor)
		}
		return m, nil
	case key.Matches(msg, k.Cancel) && m.streaming:
		return m, m.cancelReply()
	case key.Matches(msg, k.EndFollow) && !m.landing():
		return m, m.chat.ScrollToBottom()
	}

	if m.focus == focusChat {
		return m.handleChatKey(msg)
	}
	return m.handleEditorKey(msg)
}

func (m model) handleEditorKey(msg tea.KeyPressMsg) (tea.Model, tea.Cmd) {
	k := m.keys.Editor
	switch {
	case key.Matches(msg, k.Send):
		return m.submit()
	case key.Matches(msg, k.Newline):
		// Inserted here, as Crush does (see newModel).
		prev := m.input.Value()
		if m.input.HasSelection() {
			m.input.DeleteSelection()
		}
		m.input.InsertRune('\n')
		m.inputChanged(prev)
		return m, nil
	case key.Matches(msg, k.OpenEditor):
		return m, openEditor(m.input.Value())
	case key.Matches(msg, k.HistoryPrev):
		return m, m.historyUp(msg)
	case key.Matches(msg, k.HistoryNext):
		return m, m.historyDown(msg)
	case key.Matches(msg, k.CopySelection):
		if !m.input.HasSelection() {
			return m, nil
		}
		text := m.input.SelectedText()
		m.input.ClearSelection()
		return m, m.copy(text)
	case key.Matches(msg, k.CutSelection):
		if !m.input.HasSelection() {
			return m, nil
		}
		text := m.input.SelectedText()
		prev := m.input.Value()
		m.input.DeleteSelection()
		m.inputChanged(prev)
		return m, m.copy(text)
	case key.Matches(msg, k.PasteText):
		return m, pasteText
	case msg.String() == "esc":
		m.historyEscape()
		return m, nil
	case msg.String() == "pgup":
		return m, m.chat.PageUp()
	case msg.String() == "pgdown":
		return m, m.chat.PageDown()
	}
	return m, m.updateInput(msg)
}

func (m model) handleChatKey(msg tea.KeyPressMsg) (tea.Model, tea.Cmd) {
	k := m.keys.Chat
	if cmd, ok := m.chatNavigation(msg); ok {
		m.markScrollOnly()
		return m, cmd
	}
	switch {
	case key.Matches(msg, k.Expand):
		m.chat.ToggleExpandedSelected()
		return m, nil
	case key.Matches(msg, k.Copy):
		text := m.chat.HighlightContent()
		if text == "" {
			text = m.chat.SelectedText()
		}
		if text == "" {
			return m, nil
		}
		return m, m.copy(text)
	case key.Matches(msg, k.ClearHighlight):
		if m.chat.HasHighlight() {
			m.chat.ClearHighlight()
		} else {
			m.setFocus(focusEditor)
		}
		return m, nil
	}
	return m, nil
}

// chatNavigation scrolls the chat or moves its selection for a navigation
// key, as Crush does: ↑/↓ scroll a line and Shift+↑/↓ step a message.
func (m *model) chatNavigation(msg tea.KeyPressMsg) (tea.Cmd, bool) {
	k := m.keys.Chat
	switch {
	case key.Matches(msg, k.Up):
		return m.chat.ScrollLines(-1), true
	case key.Matches(msg, k.Down):
		return m.chat.ScrollLines(1), true
	case key.Matches(msg, k.UpOneItem):
		return m.chat.SelectPrev(), true
	case key.Matches(msg, k.DownOneItem):
		return m.chat.SelectNext(), true
	case key.Matches(msg, k.PageUp):
		return m.chat.PageUp(), true
	case key.Matches(msg, k.PageDown):
		return m.chat.PageDown(), true
	case key.Matches(msg, k.HalfPageUp):
		return m.chat.HalfPageUp(), true
	case key.Matches(msg, k.HalfPageDown):
		return m.chat.HalfPageDown(), true
	case key.Matches(msg, k.Home):
		return m.chat.ScrollToTop(), true
	case key.Matches(msg, k.End):
		return m.chat.ScrollToBottom(), true
	}
	return nil, false
}

// cancelReply stops the reply that is streaming on a second Esc within
// cancelTimeout of the first, so a stray Esc doesn't lose it.
func (m *model) cancelReply() tea.Cmd {
	if m.canceling {
		m.canceling = false
		m.cancel()
		return nil
	}
	m.canceling = true
	m.cancelSeq++
	seq := m.cancelSeq
	return tea.Tick(cancelTimeout, func(time.Time) tea.Msg { return cancelTimerExpiredMsg{seq} })
}

// pasteText pastes the clipboard's text, for terminals whose own paste
// doesn't reach the app.
func pasteText() tea.Msg {
	text, err := clipboard.ReadAll()
	if err != nil || text == "" {
		return pasteFailedMsg{}
	}
	return tea.PasteMsg{Content: text}
}

// handleAction carries out what a dialog asked for.
func (m model) handleAction(act action) (tea.Model, tea.Cmd) {
	switch act := act.(type) {
	case actionClose:
		m.dialogs.CloseFront()
	case actionQuit:
		return m, m.quit()
	case actionCmd:
		return m, act.cmd
	case actionSelectModel:
		m.dialogs.CloseFront()
		return m, m.selectModel(act.choice)
	case actionSelectEffort:
		m.dialogs.CloseFront()
		return m, m.selectEffort(act.effort)
	case actionRunCommand:
		m.dialogs.CloseFront()
		return m.runCommand(act.command)
	}
	return m, nil
}

func (m model) runCommand(c command) (tea.Model, tea.Cmd) {
	switch c {
	case commandModels:
		m.dialogs.Open(newModelsDialog(m.com, m.providers, m.catalog, m.active))
	case commandEffort:
		m.dialogs.Open(newEffortDialog(m.com, m.providers[m.active].Name(), m.effort[m.active]))
	case commandNewChat:
		return m, m.newChat()
	case commandOpenEditor:
		return m, openEditor(m.input.Value())
	case commandCopyReply:
		if reply := m.chat.lastReply(); reply != nil {
			return m, m.copy(reply.text)
		}
		return m, m.setStatus(statusWarn, "There is no reply to copy yet.")
	case commandHelp:
		m.fullHelp = !m.fullHelp
		m.layout()
	case commandQuit:
		return m, m.quit()
	}
	return m, nil
}

func (m *model) quit() tea.Cmd {
	if m.cancel != nil {
		m.cancel()
	}
	return tea.Quit
}

func (m *model) selectModel(choice modelChoice) tea.Cmd {
	if m.streaming {
		return m.setStatus(statusWarn, "Wait for the reply to finish (or press Esc) before switching model.")
	}
	p := m.providers[choice.provider]
	if p.Model() != choice.model {
		c, ok := p.(configurable)
		if !ok {
			return m.setStatus(statusError, p.Name()+" can't switch model.")
		}
		c.SetModel(choice.model)
		m.usage = nil
	}
	if m.active != choice.provider {
		// The last reply's usage measured another model's context, and the
		// next request is sent in a different form; the next reply sets it.
		m.usage = nil
	}
	m.active = choice.provider
	m.status = ""
	return nil
}

// selectEffort sets the active provider's reasoning effort. Each provider
// keeps its own.
func (m *model) selectEffort(effort string) tea.Cmd {
	if m.streaming {
		return m.setStatus(statusWarn, "Wait for the reply to finish (or press Esc) before changing reasoning effort.")
	}
	p := m.providers[m.active]
	c, ok := p.(configurable)
	if !ok {
		return m.setStatus(statusError, p.Name()+" can't change reasoning effort.")
	}
	c.SetEffort(effort)
	m.effort[m.active] = effort
	m.status = ""
	return nil
}

// newChat clears the conversation and goes back to the start screen.
func (m *model) newChat() tea.Cmd {
	if m.streaming {
		return m.setStatus(statusWarn, "Wait for the reply to finish (or press Esc) before starting a new chat.")
	}
	m.history = nil
	m.usage = nil
	m.chat.Clear()
	m.status = ""
	m.setFocus(focusEditor)
	m.layout()
	return nil
}

func (m *model) setFocus(f focusState) {
	m.focus = f
	if f == focusChat {
		m.input.Blur()
		m.chat.Focus()
	} else {
		m.chat.Blur()
		m.input.Focus()
	}
}

// setStatus shows a message over the help line. Info messages clear
// themselves after a few seconds.
func (m *model) setStatus(kind statusKind, text string) tea.Cmd {
	m.status, m.statusKind = text, kind
	m.statusSeq++
	if kind != statusInfo {
		return nil
	}
	seq := m.statusSeq
	return tea.Tick(infoTimeout, func(time.Time) tea.Msg { return clearStatusMsg{seq} })
}

// copy puts text on the clipboard, both through the terminal (OSC 52, which
// works over SSH) and the system clipboard.
func (m *model) copy(text string) tea.Cmd {
	return tea.Batch(
		tea.SetClipboard(text),
		func() tea.Msg {
			_ = clipboard.WriteAll(text)
			return nil
		},
		m.setStatus(statusInfo, "Copied to clipboard."),
	)
}

func (m model) handleMouseClick(msg tea.MouseClickMsg) (tea.Model, tea.Cmd) {
	if m.dialogs.HasDialogs() || msg.Button != tea.MouseLeft {
		return m, nil
	}
	pt := image.Pt(msg.X, msg.Y)
	switch {
	case pt.In(m.rects.main) && !m.landing():
		m.chat.ClearHighlight()
		handled, cmd := m.chat.HandleMouseDown(msg.X-m.rects.main.Min.X, msg.Y-m.rects.main.Min.Y)
		if handled {
			m.setFocus(focusChat)
		}
		return m, cmd
	case pt.In(m.rects.editor):
		m.setFocus(focusEditor)
		if pt.In(m.inputArea()) {
			m.inputSelecting = true
			at := pt.Sub(m.inputArea().Min)
			m.input.BeginSelection(at.X, at.Y)
		}
	}
	return m, nil
}

// inputArea is where the input box is drawn: its own height, one row below
// the top of the editor area (see draw).
func (m *model) inputArea() image.Rectangle {
	origin := image.Pt(m.rects.editor.Min.X, m.rects.editor.Min.Y+1)
	return image.Rectangle{Min: origin, Max: origin.Add(image.Pt(m.rects.editor.Dx(), m.input.Height()))}
}

// updateInput passes msg to the input box, which grows and shrinks with its
// text. Editing leaves the prompt history.
func (m *model) updateInput(msg tea.Msg) tea.Cmd {
	prev := m.input.Value()
	var cmd tea.Cmd
	m.input, cmd = m.input.Update(msg)
	m.inputChanged(prev)
	return cmd
}

// inputChanged leaves the prompt history if the input's text is no longer
// prev, and fits the layout to the input's height.
func (m *model) inputChanged(prev string) {
	if m.input.Value() != prev {
		m.prompts.index = -1
		m.prompts.draft = m.input.Value()
	}
	m.layout()
}

// editorPrompt is Crush's input prompt: a ">" on the first line and ":::"
// on the lines below it.
func editorPrompt(st *Styles) func(textarea.PromptInfo) string {
	return func(info textarea.PromptInfo) string {
		switch {
		case info.LineNumber == 0 && info.Focused:
			return st.Editor.PromptIcon.Render()
		case info.LineNumber == 0:
			return "::: "
		case info.Focused:
			return st.Editor.PromptDots.Render()
		default:
			return st.Editor.PromptDotsBlurred.Render()
		}
	}
}

func (m model) submit() (tea.Model, tea.Cmd) {
	text := strings.TrimSpace(m.input.Value())
	if text == "" || m.streaming {
		return m, nil
	}
	provider := m.providers[m.active]

	m.history = append(m.history, Turn{Role: RoleUser, Text: text})
	m.chat.Append(newUserItem(m.com, text), newAssistantItem(m.com, provider.Name(), provider.Model()))
	m.chat.follow = true
	m.sentInput = m.input.Value()
	m.prompts.add(m.sentInput)
	m.input.Reset()
	m.status = ""
	m.layout() // leaves the start screen on the first message

	ctx, cancel := context.WithCancel(context.Background())
	m.cancel = cancel
	m.events = coalesce(ctx, provider.Stream(ctx, slices.Clone(m.history)), streamDebounce)
	m.streaming = true
	return m, tea.Batch(waitForEvent(m.events), renderTick())
}

func (m model) handleStreamEvent(msg streamEventMsg) (tea.Model, tea.Cmd) {
	last := m.chat.lastAssistant()
	if !msg.ok {
		// Providers end with Done or Err, except that the Err can be dropped
		// once the request is cancelled.
		if last != nil && last.pending {
			m.fail(context.Canceled)
		}
		m.streaming = false
		m.canceling = false
		m.cancel()
		m.cancel = nil
		m.events = nil
		return m, nil
	}

	switch event := msg.event; {
	case event.Delta != "":
		last.appendText(event.Delta)
	case event.Thinking != "":
		last.appendThinking(event.Thinking)
	case event.Done != nil:
		m.history = append(m.history, *event.Done)
		last.complete(event.Done.Text, event.Note, event.Usage)
		if event.Usage != nil {
			m.usage = event.Usage
		}
	case event.Err != nil:
		m.fail(event.Err)
	}
	return m, waitForEvent(m.events)
}

// fail ends the reply that is streaming with err, as Crush does: the reply
// stays in the chat, with the reason under any text that arrived. That text
// joins the history, so the model sees what it said. A reply without text
// leaves nothing to answer, so the message is taken out of the history and
// put back in the input to be resent.
func (m *model) fail(err error) {
	last := m.chat.lastAssistant()
	last.fail(describeError(err), errors.Is(err, context.Canceled))
	if strings.TrimSpace(last.text) != "" {
		m.history = append(m.history, Turn{Role: RoleAssistant, Text: last.text})
		return
	}
	m.history = m.history[:len(m.history)-1]
	if strings.TrimSpace(m.input.Value()) == "" {
		m.input.SetValue(m.sentInput)
		m.layout()
	}
}

func waitForEvent(events <-chan StreamEvent) tea.Cmd {
	return func() tea.Msg {
		event, ok := <-events
		return streamEventMsg{event: event, ok: ok}
	}
}

func renderTick() tea.Cmd {
	return tea.Tick(renderInterval, func(time.Time) tea.Msg { return renderTickMsg{} })
}

// layout places everything for the current screen size, state and input
// height. The command re-renders the messages if the chat's width changed.
func (m *model) layout() tea.Cmd {
	if m.width == 0 {
		return nil
	}
	m.rects = m.generateLayout()
	m.input.SetWidth(max(m.rects.editor.Dx(), 1))
	if m.input.Height()+2 != m.rects.editor.Dy() {
		// The input re-measured its height at the new width.
		m.rects = m.generateLayout()
	}
	return m.chat.SetSize(m.rects.main.Dx(), max(m.rects.main.Dy(), 1))
}

// View draws the screen, or reuses the frame drawn for the same scroll
// position when only scrolling has happened since.
func (m model) View() tea.View {
	st := m.com.styles
	v := tea.NewView("")
	v.AltScreen = true
	v.BackgroundColor = st.BgBase
	v.MouseMode = tea.MouseModeCellMotion
	v.WindowTitle = "chat"
	if m.width == 0 {
		return v
	}

	key, cacheable := m.currentFrameKey()
	if cacheable {
		if content, cursor, ok := m.frames.get(key); ok {
			v.Content, v.Cursor = content, cursor
			return v
		}
	}
	v.Content, v.Cursor = m.draw()
	if cacheable {
		m.storeFrame(key, v.Content, v.Cursor)
	}
	return v
}

// draw paints each part into its rectangle on a screen-sized canvas, with
// any dialogs on top, and returns it with the cursor.
func (m model) draw() (string, *tea.Cursor) {
	st := m.com.styles
	l := m.rects
	canvas := uv.NewScreenBuffer(m.width, m.height)
	draw := func(s string, r image.Rectangle) {
		if !r.Empty() {
			uv.NewStyledString(s).Draw(canvas, r)
		}
	}

	switch {
	case m.landing():
		draw(wideLogo(st, l.header.Dx()), l.header)
		draw(m.landingView(l.main.Dx(), l.main.Dy()), l.main)
	case m.compact():
		draw(m.compactHeader(l.header.Dx()), l.header)
		m.chat.Draw(canvas, l.main)
	default:
		m.chat.Draw(canvas, l.main)
		draw(m.sidebarView(l.sidebar.Dx(), l.sidebar.Dy()), l.sidebar)
	}
	// The input has a blank row above and below it, like Crush's editor.
	draw("\n"+m.input.View(), l.editor)
	draw(m.helpView(l.help.Dx()), l.help)

	var cursor *tea.Cursor
	if m.dialogs.HasDialogs() {
		cursor = m.dialogs.Draw(canvas, canvas.Bounds())
	} else if c := m.input.Cursor(); c != nil && m.focus == focusEditor {
		c.X += l.editor.Min.X
		c.Y += l.editor.Min.Y + 1
		cursor = c
	}
	return canvas.Render(), cursor
}
