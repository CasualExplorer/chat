package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/charmbracelet/x/ansi"
)

// fakeProvider replies with a fixed sequence of events.
type fakeProvider struct {
	name    string
	model   string
	effort  string
	events  []StreamEvent
	history []Turn // what the last Stream call received
}

func (p *fakeProvider) Name() string { return p.name }
func (p *fakeProvider) Model() string {
	if p.model == "" {
		return "fake-model"
	}
	return p.model
}
func (p *fakeProvider) SetModel(model string)   { p.model = model }
func (p *fakeProvider) SetEffort(effort string) { p.effort = effort }

func (p *fakeProvider) Stream(ctx context.Context, history []Turn) <-chan StreamEvent {
	p.history = history
	out := make(chan StreamEvent, len(p.events))
	for _, ev := range p.events {
		out <- ev
	}
	close(out)
	return out
}

// blockingProvider streams one delta and then waits to be cancelled, like a
// real provider mid-reply. Once cancelled it either reports the error or, as
// send() may do when the context is done, closes without reporting it.
type blockingProvider struct {
	dropErr bool
}

func (p *blockingProvider) Name() string  { return "Blocking" }
func (p *blockingProvider) Model() string { return "fake-model" }

func (p *blockingProvider) Stream(ctx context.Context, history []Turn) <-chan StreamEvent {
	out := make(chan StreamEvent)
	go func() {
		defer close(out)
		out <- StreamEvent{Delta: "partial"}
		<-ctx.Done()
		if !p.dropErr {
			out <- StreamEvent{Err: ctx.Err()}
		}
	}()
	return out
}

func update(t *testing.T, m model, msg tea.Msg) model {
	t.Helper()
	next, _ := m.Update(msg)
	return next.(model)
}

// updateCmd is update that also returns the command.
func updateCmd(t *testing.T, m model, msg tea.Msg) (model, tea.Cmd) {
	t.Helper()
	next, cmd := m.Update(msg)
	return next.(model), cmd
}

func press(t *testing.T, m model, keys ...tea.KeyPressMsg) model {
	t.Helper()
	for _, k := range keys {
		m = update(t, m, k)
	}
	return m
}

var (
	keyEnter = tea.KeyPressMsg{Code: tea.KeyEnter}
	keyEsc   = tea.KeyPressMsg{Code: tea.KeyEscape}
	keyTab   = tea.KeyPressMsg{Code: tea.KeyTab}
	keyUp    = tea.KeyPressMsg{Code: tea.KeyUp}
	keyDown  = tea.KeyPressMsg{Code: tea.KeyDown}
	keySpace = tea.KeyPressMsg{Code: tea.KeySpace, Text: " "}
)

func ctrl(r rune) tea.KeyPressMsg { return tea.KeyPressMsg{Code: r, Mod: tea.ModCtrl} }
func char(r rune) tea.KeyPressMsg { return tea.KeyPressMsg{Code: r, Text: string(r)} }

// sendMessage types text, presses Enter and feeds every stream event back in.
func sendMessage(t *testing.T, m model, text string) model {
	t.Helper()
	m.input.SetValue(text)
	m = update(t, m, keyEnter)
	for m.events != nil {
		m = update(t, m, waitForEvent(m.events)())
	}
	return m
}

func newTestModel(providers ...Provider) model {
	m := newModel(providers, 0)
	next, _ := m.Update(tea.WindowSizeMsg{Width: 80, Height: 24})
	return next.(model)
}

// reply returns the chat's i'th message, which must be a reply.
func reply(t *testing.T, m model, i int) *assistantItem {
	t.Helper()
	a, ok := m.chat.item(i).(*assistantItem)
	if !ok {
		t.Fatalf("message %d is %T, not a reply", i, m.chat.item(i))
	}
	return a
}

// viewText is the whole conversation as plain text, without styling.
func viewText(m model) string {
	var parts []string
	for i := range m.chat.Len() {
		parts = append(parts, m.chat.item(i).Render(m.chat.list.Width()))
	}
	return ansi.Strip(strings.Join(parts, "\n\n"))
}

// screenText is what View draws, as plain text.
func screenText(m model) string {
	return ansi.Strip(m.View().Content)
}

func TestReplyIsStreamedIntoHistory(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{
		{Delta: "Hel"}, {Delta: "lo"},
		{Done: &Turn{Role: RoleAssistant, Text: "Hello"}, Note: "cut off"},
	}}
	m := sendMessage(t, newTestModel(p), "hi")

	if m.streaming || m.cancel != nil {
		t.Fatal("model still streaming after the stream closed")
	}
	if len(m.history) != 2 || m.history[0].Text != "hi" || m.history[1].Text != "Hello" {
		t.Fatalf("history = %+v", m.history)
	}
	if got := reply(t, m, 1); got.text != "Hello" || got.note != "cut off" || got.pending {
		t.Fatalf("reply = %+v", got)
	}
	if m.input.Value() != "" {
		t.Fatalf("input not cleared: %q", m.input.Value())
	}
	if !strings.Contains(screenText(m), "Hello") {
		t.Fatalf("reply not drawn:\n%s", screenText(m))
	}
}

func TestFailedReplyKeepsItsText(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{
		{Done: &Turn{Role: RoleAssistant, Text: "first reply"}},
	}}
	m := sendMessage(t, newTestModel(p), "first")

	p.events = []StreamEvent{{Delta: "partial"}, {Err: errors.New("boom")}}
	m = sendMessage(t, m, "second")

	if m.chat.Len() != 4 || len(m.history) != 4 || m.history[3].Text != "partial" {
		t.Fatalf("failed reply not kept: messages=%d history=%+v", m.chat.Len(), m.history)
	}
	if got := reply(t, m, 3); got.pending || got.failure != "boom" || got.text != "partial" {
		t.Fatalf("reply = %+v", got)
	}
	if content := viewText(m); !strings.Contains(content, "partial") || !strings.Contains(content, "ERROR  boom") {
		t.Fatalf("failed reply rendered as:\n%s", content)
	}
	if m.input.Value() != "" || m.status != "" {
		t.Fatalf("input = %q, status = %q", m.input.Value(), m.status)
	}

	// The next request carries on from the partial reply.
	p.events = []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "ok"}}}
	m = sendMessage(t, m, "third")
	if len(p.history) != 5 || p.history[3].Text != "partial" || p.history[4].Text != "third" {
		t.Fatalf("provider received %+v", p.history)
	}
}

func TestFailedReplyWithoutTextPutsTheMessageBack(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Thinking: "hmm"}, {Err: errors.New("boom")}}}
	m := sendMessage(t, newTestModel(p), "hi")

	if m.chat.Len() != 2 || m.landing() {
		t.Fatalf("failed exchange not kept in the chat: messages=%d", m.chat.Len())
	}
	if len(m.history) != 0 {
		t.Fatalf("a message without a reply stayed in the history: %+v", m.history)
	}
	if m.input.Value() != "hi" {
		t.Fatalf("input not restored: %q", m.input.Value())
	}
	if content := viewText(m); !strings.Contains(content, "ERROR  boom") || strings.Contains(content, "no text in this reply") {
		t.Fatalf("failed reply rendered as:\n%s", content)
	}
}

func TestCancelNeedsASecondEsc(t *testing.T) {
	for _, dropErr := range []bool{false, true} {
		t.Run(fmt.Sprintf("dropErr=%v", dropErr), func(t *testing.T) {
			m := newTestModel(&blockingProvider{dropErr: dropErr})
			m.input.SetValue("hi")
			m = update(t, m, keyEnter)
			m = update(t, m, waitForEvent(m.events)()) // the partial delta

			m, cmd := updateCmd(t, m, keyEsc)
			if !m.streaming || !m.canceling || cmd == nil {
				t.Fatal("one esc stopped the reply")
			}
			if !strings.Contains(screenText(m), "press again to cancel") {
				t.Fatalf("no hint to press esc again:\n%s", screenText(m))
			}
			// The wait for a second press runs out.
			m = update(t, m, cmd())
			if m.canceling {
				t.Fatal("the first esc was remembered after its timer ran out")
			}

			m = press(t, m, keyEsc, keyEsc)
			for m.events != nil {
				m = update(t, m, waitForEvent(m.events)())
			}
			if m.streaming || m.canceling {
				t.Fatal("model still streaming after cancel")
			}
			if len(m.history) != 2 || m.history[1].Text != "partial" || m.chat.Len() != 2 {
				t.Fatalf("cancelled reply not kept: history=%+v messages=%d", m.history, m.chat.Len())
			}
			if got := reply(t, m, 1); !got.canceled || got.text != "partial" {
				t.Fatalf("reply = %+v", got)
			}
			if content := viewText(m); !strings.Contains(content, "Canceled") || strings.Contains(content, "via Blocking") {
				t.Fatalf("cancelled reply rendered as:\n%s", content)
			}
			if m.input.Value() != "" {
				t.Fatalf("input = %q", m.input.Value())
			}
		})
	}
}

func TestEmptyReplyIsNotShownAsThinking(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{
		{Done: &Turn{Role: RoleAssistant}, Note: "Reply cut off: reached the max_tokens limit."},
	}}
	m := sendMessage(t, newTestModel(p), "hi")

	content := viewText(m)
	if strings.Contains(content, "thinking") || !strings.Contains(content, "no text in this reply") {
		t.Fatalf("empty reply rendered as:\n%s", content)
	}
}

func TestModelPickerSwitchesProviderAndKeepsConversation(t *testing.T) {
	a := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "from A"}}}}
	b := &fakeProvider{name: "B", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "from B"}}}}
	m := sendMessage(t, newTestModel(a, b), "one")

	m = update(t, m, ctrl('l'))
	if !m.dialogs.Contains("models") {
		t.Fatal("ctrl+l did not open the model picker")
	}
	if !strings.Contains(screenText(m), "Switch Model") {
		t.Fatalf("model picker not drawn:\n%s", screenText(m))
	}
	m = press(t, m, keyDown, keyEnter) // A's model is current; B's is next
	if m.active != 1 || m.dialogs.HasDialogs() {
		t.Fatalf("active = %d, dialogs open = %v", m.active, m.dialogs.HasDialogs())
	}
	m = sendMessage(t, m, "two")

	if len(b.history) != 3 || b.history[1].Text != "from A" {
		t.Fatalf("provider B did not receive the earlier conversation: %+v", b.history)
	}
	if got := reply(t, m, 3); got.provider != "B" || got.model != "fake-model" {
		t.Fatalf("reply from %q/%q", got.provider, got.model)
	}
}

func TestModelPickerFiltersAndSetsModel(t *testing.T) {
	a := &fakeProvider{name: "Anthropic", model: "claude-opus-5-5"}
	m := newTestModel(a)
	m = update(t, m, ctrl('l'))
	for _, r := range "sonnet" {
		m = update(t, m, char(r))
	}
	m = update(t, m, keyEnter)
	if a.Model() != "claude-sonnet-5-5" {
		t.Fatalf("model = %q", a.Model())
	}
	if !strings.Contains(screenText(m), "claude-sonnet-5-5") {
		t.Fatalf("new model not shown:\n%s", screenText(m))
	}
}

func TestCommandPaletteRunsCommands(t *testing.T) {
	p := &fakeProvider{name: "A"}
	m := newTestModel(p)
	m.effort[0] = "medium"

	m = update(t, m, ctrl('p'))
	if !m.dialogs.Contains("commands") || !strings.Contains(screenText(m), "Commands") {
		t.Fatalf("ctrl+p did not open the command palette:\n%s", screenText(m))
	}
	for _, r := range "effort" {
		m = update(t, m, char(r))
	}
	m = update(t, m, keyEnter)
	if !m.dialogs.Contains("effort") || m.dialogs.Contains("commands") {
		t.Fatal("the effort command did not replace the palette with the effort picker")
	}
	m = press(t, m, keyDown, keyEnter) // medium is current; high is next
	if m.effort[0] != "high" || p.effort != "high" || m.dialogs.HasDialogs() {
		t.Fatalf("effort = %q, provider effort = %q", m.effort[0], p.effort)
	}
	if !strings.Contains(screenText(m), "Reasoning High") {
		t.Fatalf("new effort not shown:\n%s", screenText(m))
	}
}

func TestEachProviderKeepsItsEffort(t *testing.T) {
	a, b := &fakeProvider{name: "A", effort: "medium"}, &fakeProvider{name: "B", effort: "medium"}
	m := newTestModel(a, b)
	m.effort = []string{"medium", "medium"}

	m.selectEffort("max")
	m.selectModel(modelChoice{provider: 1, model: b.Model()})
	if !strings.Contains(screenText(m), "Reasoning Medium") {
		t.Fatalf("B doesn't show its own effort:\n%s", screenText(m))
	}
	m.selectEffort("low")
	if a.effort != "max" || b.effort != "low" || m.effort[0] != "max" || m.effort[1] != "low" {
		t.Fatalf("efforts: A %q, B %q, model %q", a.effort, b.effort, m.effort)
	}

	m = update(t, m, ctrl('p'))
	for _, r := range "effort" {
		m = update(t, m, char(r))
	}
	m = update(t, m, keyEnter)
	if screen := screenText(m); !strings.Contains(screen, "Reasoning Effort · B") {
		t.Fatalf("effort picker doesn't name the provider:\n%s", screen)
	}
}

func TestEscClosesDialog(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	m = press(t, m, ctrl('p'), keyEsc)
	if m.dialogs.HasDialogs() {
		t.Fatal("esc did not close the palette")
	}
}

func TestQuitAsksFirst(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	m, cmd := updateCmd(t, m, ctrl('c'))
	if cmd != nil || !m.dialogs.Contains("quit") {
		t.Fatal("ctrl+c did not ask before quitting")
	}
	if !strings.Contains(screenText(m), "Are you sure you want to quit?") {
		t.Fatalf("quit dialog not drawn:\n%s", screenText(m))
	}

	_, cmd = updateCmd(t, m, ctrl('c'))
	if cmd == nil {
		t.Fatal("a second ctrl+c did not quit")
	}
	if _, ok := cmd().(tea.QuitMsg); !ok {
		t.Fatal("a second ctrl+c did not quit")
	}

	m = press(t, newTestModel(&fakeProvider{name: "A"}), ctrl('c'), char('n'))
	if m.dialogs.HasDialogs() {
		t.Fatal("n did not close the quit dialog")
	}
}

func TestThinkingSummaryIsShownAboveReply(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{
		{Thinking: "**Planning**"}, {Thinking: "\n\nWeighing options"},
		{Delta: "Answer"},
		{Done: &Turn{Role: RoleAssistant, Text: "Answer"}},
	}}
	m := sendMessage(t, newTestModel(p), "hi")

	if got := reply(t, m, 1).thinking; got != "**Planning**\n\nWeighing options" {
		t.Fatalf("thinking = %q", got)
	}
	if m.history[1].Text != "Answer" {
		t.Fatalf("summary leaked into the reply sent back to providers: %q", m.history[1].Text)
	}
	content := viewText(m)
	summary, answer := strings.Index(content, "Weighing options"), strings.Index(content, "Answer")
	if summary < 0 || answer < 0 || summary > answer {
		t.Fatalf("summary not rendered above the reply:\n%s", content)
	}
	if strings.Contains(content, "**") || strings.Contains(content, "thinking…") {
		t.Fatalf("unexpected markup or placeholder:\n%s", content)
	}
}

func TestLongThinkingIsCollapsedUntilExpanded(t *testing.T) {
	var lines []string
	for i := range 30 {
		lines = append(lines, fmt.Sprintf("step %d", i))
	}
	p := &fakeProvider{name: "A", events: []StreamEvent{
		{Thinking: strings.Join(lines, "\n\n")},
		{Done: &Turn{Role: RoleAssistant, Text: "Answer"}},
	}}
	m := sendMessage(t, newTestModel(p), "hi")

	content := viewText(m)
	if strings.Contains(content, "step 0") || !strings.Contains(content, "step 29") || !strings.Contains(content, "lines hidden") {
		t.Fatalf("long thinking not collapsed to its last lines:\n%s", content)
	}

	// Tab moves to the chat, which selects the newest message; space
	// expands its thinking.
	m = press(t, m, keyTab)
	if m.focus != focusChat || m.chat.list.Selected() != 1 {
		t.Fatalf("focus = %v, selected = %d", m.focus, m.chat.list.Selected())
	}
	m = press(t, m, keySpace)
	if content := viewText(m); !strings.Contains(content, "step 0") || strings.Contains(content, "lines hidden") {
		t.Fatalf("thinking not expanded:\n%s", content)
	}
	m = press(t, m, keySpace)
	if strings.Contains(viewText(m), "step 0") {
		t.Fatal("thinking not collapsed again")
	}
}

func TestChatFocusSelectsAndCopiesMessages(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "the reply"}}}}
	m := sendMessage(t, newTestModel(p), "the question")

	m = press(t, m, keyTab, char('K'))
	if got := m.chat.SelectedText(); got != "the question" {
		t.Fatalf("selected %q after K", got)
	}
	if !strings.Contains(screenText(m), "▌") {
		t.Fatalf("selected message not marked:\n%s", screenText(m))
	}
	m, cmd := updateCmd(t, m, char('c'))
	if cmd == nil || m.status != "Copied to clipboard." || m.statusKind != statusInfo {
		t.Fatalf("c did not copy: status %q", m.status)
	}
	m = press(t, m, char('J'))
	if got := m.chat.SelectedText(); got != "the reply" {
		t.Fatalf("selected %q after J", got)
	}

	m = press(t, m, keyEsc)
	if m.focus != focusEditor || !m.input.Focused() {
		t.Fatal("esc did not return to the editor")
	}
}

func TestMouseSelectionCopiesText(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "hello world"}}}}
	m := sendMessage(t, newTestModel(p), "question")
	_ = m.View() // lay out the list

	// Find the row of the reply's text on screen.
	main := m.rects.main
	row := -1
	for y, line := range strings.Split(screenText(m), "\n") {
		if strings.Contains(line, "hello world") {
			row = y
			break
		}
	}
	if row < 0 {
		t.Fatalf("reply not on screen:\n%s", screenText(m))
	}
	x := main.Min.X + prefixWidth
	m, _ = updateCmd(t, m, tea.MouseClickMsg{X: x, Y: row, Button: tea.MouseLeft})
	m = update(t, m, tea.MouseMotionMsg{X: x + 5, Y: row, Button: tea.MouseLeft})
	m, cmd := updateCmd(t, m, tea.MouseReleaseMsg{X: x + 5, Y: row, Button: tea.MouseLeft})
	if cmd == nil || m.status != "Copied to clipboard." {
		t.Fatalf("selection not copied: status %q", m.status)
	}
	if got := m.chat.HighlightContent(); got != "hello" {
		t.Fatalf("selected %q", got)
	}
}

func TestPromptHistory(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "ok"}}}}
	m := sendMessage(t, newTestModel(p), "first")
	m = sendMessage(t, m, "second")

	m.input.SetValue("draft")
	m.input.MoveToBegin()
	m = press(t, m, keyUp)
	if got := m.input.Value(); got != "second" {
		t.Fatalf("up showed %q", got)
	}
	m = press(t, m, keyUp)
	if got := m.input.Value(); got != "first" {
		t.Fatalf("second up showed %q", got)
	}
	m.input.MoveToEnd()
	m = press(t, m, keyDown)
	if got := m.input.Value(); got != "second" {
		t.Fatalf("down showed %q", got)
	}
	m = press(t, m, keyEsc)
	if got := m.input.Value(); got != "draft" {
		t.Fatalf("esc restored %q", got)
	}
}

func TestNewChatClearsConversation(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "ok"}}}}
	m := sendMessage(t, newTestModel(p), "hi")
	m = update(t, m, ctrl('n'))
	if len(m.history) != 0 || m.chat.Len() != 0 || !m.landing() {
		t.Fatalf("new chat kept history=%d messages=%d", len(m.history), m.chat.Len())
	}
}

func TestFullHelpToggles(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	before := m.rects.editor
	m = update(t, m, ctrl('g'))
	if !m.fullHelp || !strings.Contains(screenText(m), "open editor") {
		t.Fatalf("ctrl+g did not show the full help:\n%s", screenText(m))
	}
	if m.rects.editor.Max.Y >= before.Max.Y {
		t.Fatal("the full help did not make room for itself")
	}
}

func TestFinishedMessagesAreNotRerendered(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "ok"}}}}
	m := sendMessage(t, newTestModel(p), "hi")
	_ = m.View()
	first := reply(t, m, 1)
	version := first.Version()

	p.events = []StreamEvent{{Delta: "more"}, {Done: &Turn{Role: RoleAssistant, Text: "more"}}}
	m = sendMessage(t, m, "again")
	_ = m.View()
	if first.Version() != version {
		t.Fatal("an earlier reply changed while a later one streamed")
	}
}

// chanProvider streams whatever the test sends on its channel.
type chanProvider struct{ events chan StreamEvent }

func (p *chanProvider) Name() string  { return "Chan" }
func (p *chanProvider) Model() string { return "fake-model" }

func (p *chanProvider) Stream(ctx context.Context, history []Turn) <-chan StreamEvent {
	return p.events
}

func TestSpinnerShowsUntilReplyText(t *testing.T) {
	p := &chanProvider{events: make(chan StreamEvent, 1)}
	m := newTestModel(p)
	m.input.SetValue("hi")
	m = update(t, m, keyEnter)
	defer m.cancel()
	receive := func(ev StreamEvent) {
		p.events <- ev
		m = update(t, m, waitForEvent(m.events)())
		m = update(t, m, renderTickMsg{})
	}

	if !reply(t, m, 1).spinning() || !strings.Contains(viewText(m), "0s") {
		t.Fatalf("no spinner while waiting:\n%s", viewText(m))
	}
	receive(StreamEvent{Thinking: "pondering"})
	if !strings.Contains(viewText(m), "Thinking 0s") {
		t.Fatalf("spinner not labelled while thinking:\n%s", viewText(m))
	}
	receive(StreamEvent{Delta: "Answer"})
	if reply(t, m, 1).spinning() || strings.Contains(viewText(m), "Thinking") {
		t.Fatalf("spinner still shown once the reply started:\n%s", viewText(m))
	}
}

func TestInputGrowsWithItsText(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	before := m.rects.main.Dy()
	for range 4 {
		m = update(t, m, ctrl('j'))
	}
	if m.input.Height() != 5 || m.rects.main.Dy() != before-2 || m.chat.list.Height() != before-2 {
		t.Fatalf("input height %d, conversation height %d (was %d)", m.input.Height(), m.rects.main.Dy(), before)
	}
}

// The input box stops growing at inputMaxLines, but the text doesn't.
func TestNewlinesPastTheInputsMaxHeight(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	for range inputMaxLines + 5 {
		m = press(t, m, char('a'), tea.KeyPressMsg{Code: tea.KeyEnter, Mod: tea.ModShift})
	}
	if lines := strings.Count(m.input.Value(), "\n") + 1; lines != inputMaxLines+6 {
		t.Fatalf("input has %d lines, want %d", lines, inputMaxLines+6)
	}
	if m.input.Height() > inputMaxLines {
		t.Fatalf("input box grew to %d rows", m.input.Height())
	}
}

func TestNewlineReplacesTheSelection(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	m.input.SetValue("hello")
	m = press(t, m, tea.KeyPressMsg{Code: 'a', Mod: tea.ModCtrl | tea.ModShift}, ctrl('j'))
	if m.input.Value() != "\n" {
		t.Fatalf("input = %q", m.input.Value())
	}
}

func TestLayoutFollowsScreenSize(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "Hello"}}}}
	m := newModel([]Provider{p}, 0)
	m = update(t, m, tea.WindowSizeMsg{Width: 140, Height: 40})
	if !m.rects.sidebar.Empty() || m.rects.header.Dy() != landingHeaderHeight {
		t.Fatalf("start screen layout = %+v", m.rects)
	}

	m = sendMessage(t, m, "hi")
	if m.rects.sidebar.Dx() != sidebarWidth-1 || !m.rects.header.Empty() {
		t.Fatalf("wide chat layout = %+v", m.rects)
	}
	if got := m.rects.main.Max.X; got >= m.rects.sidebar.Min.X {
		t.Fatalf("conversation (to x=%d) overlaps the sidebar (from x=%d)", got, m.rects.sidebar.Min.X)
	}

	m = update(t, m, tea.WindowSizeMsg{Width: 100, Height: 40})
	if !m.rects.sidebar.Empty() || m.rects.header.Dy() != 1 {
		t.Fatalf("compact chat layout = %+v", m.rects)
	}
	if m.chat.list.Width() != m.rects.main.Dx() {
		t.Fatalf("messages laid out %d wide, conversation is %d", m.chat.list.Width(), m.rects.main.Dx())
	}
}

func TestStatusIsShownOverHelp(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	m.setStatus(statusError, "boom")
	if help := ansi.Strip(m.helpView(80)); !strings.HasPrefix(help, " ERROR  boom") {
		t.Fatalf("help line = %q", help)
	}
}

// longChat is a chat whose messages are taller than the screen.
func longChat(t *testing.T) model {
	t.Helper()
	long := strings.Repeat("line\n\n", 30)
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: long}}}}
	m := newTestModel(p)
	for range 3 {
		m = sendMessage(t, m, "question")
	}
	_ = m.View()
	return m
}

func TestArrowsScrollALineAndShiftArrowsAMessage(t *testing.T) {
	m := press(t, longChat(t), keyTab)
	last := m.chat.Len() - 1
	if m.chat.list.Selected() != last {
		t.Fatalf("selected %d on focus, want %d", m.chat.list.Selected(), last)
	}

	offset := m.chat.list.Offset()
	m = press(t, m, keyUp)
	if got := m.chat.list.Offset(); got != offset-1 {
		t.Fatalf("up scrolled from %d to %d, want one line", offset, got)
	}
	if m.chat.list.Selected() != last || m.chat.follow {
		t.Fatalf("selected %d, follow %v after one line up", m.chat.list.Selected(), m.chat.follow)
	}

	m = press(t, m, tea.KeyPressMsg{Code: tea.KeyUp, Mod: tea.ModShift})
	if m.chat.list.Selected() != last-1 || !m.chat.list.SelectedItemInView() {
		t.Fatalf("shift+up selected %d", m.chat.list.Selected())
	}

	// Scrolling the selected message out of view selects one in view.
	m = press(t, m, tea.KeyPressMsg{Code: tea.KeyHome})
	for range 40 {
		m = press(t, m, keyDown)
	}
	if !m.chat.list.SelectedItemInView() {
		t.Fatalf("selection %d left behind while scrolling", m.chat.list.Selected())
	}

	m = press(t, m, tea.KeyPressMsg{Code: tea.KeyPgUp})
	if start, _ := m.chat.list.VisibleItemIndices(); m.chat.list.Selected() != start {
		t.Fatalf("page up selected %d, want the first in view, %d", m.chat.list.Selected(), start)
	}

	m = press(t, m, tea.KeyPressMsg{Code: tea.KeyEnd, Mod: tea.ModCtrl})
	if !m.chat.follow || m.chat.list.Selected() != last {
		t.Fatalf("ctrl+end: follow %v, selected %d", m.chat.follow, m.chat.list.Selected())
	}
}

func TestScrollingReusesFrames(t *testing.T) {
	m := press(t, longChat(t), keyTab)
	m = press(t, m, keyUp)
	first := m.View().Content
	if len(m.frames.entries) != 1 {
		t.Fatalf("%d frames kept after scrolling", len(m.frames.entries))
	}
	m = press(t, m, keyDown)
	_ = m.View()
	m = press(t, m, keyUp)
	if len(m.frames.entries) != 2 || m.View().Content != first {
		t.Fatalf("scrolling back didn't reuse the frame: %d kept", len(m.frames.entries))
	}

	// Anything but scrolling drops the frames kept, leaving just the new one.
	m = press(t, m, char('c'))
	if !strings.Contains(m.View().Content, "Copied") || len(m.frames.entries) != 1 {
		t.Fatalf("a copy left %d frames, or the status wasn't drawn", len(m.frames.entries))
	}
}

func TestWheelBurstsAreMerged(t *testing.T) {
	now := time.Unix(0, 0)
	f := newInputFilter()
	f.now = func() time.Time { return now }
	down := tea.MouseWheelMsg{Button: tea.MouseWheelDown}

	if got, ok := f.filter(nil, down).(coalescedWheelMsg); !ok || got.DeltaY != 1 {
		t.Fatalf("first wheel event = %+v", got)
	}
	for range 3 {
		now = now.Add(time.Millisecond)
		if got := f.filter(nil, down); got != nil {
			t.Fatalf("wheel event within the interval passed: %+v", got)
		}
	}
	now = now.Add(inputFilterInterval)
	if got, ok := f.filter(nil, down).(coalescedWheelMsg); !ok || got.DeltaY != 4 {
		t.Fatalf("merged wheel event = %+v, want 4 steps", got)
	}
	if got := f.filter(nil, keyEnter); got != keyEnter {
		t.Fatal("a key press was filtered")
	}

	m := longChat(t)
	offset := m.chat.list.Offset()
	m = update(t, m, coalescedWheelMsg{Mouse: tea.Mouse{X: m.rects.main.Min.X, Y: m.rects.main.Min.Y}, DeltaY: -2})
	if got := m.chat.list.Offset(); got != offset-2*wheelLines {
		t.Fatalf("wheel scrolled from %d to %d", offset, got)
	}
}

func TestInputSelectionCopyAndCut(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	m.input.SetValue("hello world")
	selectAll := tea.KeyPressMsg{Code: 'a', Mod: tea.ModCtrl | tea.ModShift}
	m = press(t, m, selectAll)
	if !m.input.HasSelection() {
		t.Fatal("ctrl+shift+a selected nothing")
	}
	m, cmd := updateCmd(t, m, tea.KeyPressMsg{Code: 'c', Mod: tea.ModCtrl | tea.ModShift})
	if cmd == nil || m.status != "Copied to clipboard." || m.input.Value() != "hello world" || m.dialogs.HasDialogs() {
		t.Fatalf("copy: status %q, input %q", m.status, m.input.Value())
	}

	m = press(t, m, selectAll)
	m, cmd = updateCmd(t, m, tea.KeyPressMsg{Code: 'x', Mod: tea.ModCtrl | tea.ModShift})
	if cmd == nil || m.input.Value() != "" {
		t.Fatalf("cut left %q", m.input.Value())
	}
}

func TestMouseInInputPlacesCursorAndSelects(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	m.input.SetValue("hello world")
	area := m.inputArea()
	line := strings.Split(screenText(m), "\n")[area.Min.Y]
	before, _, ok := strings.Cut(line, "world")
	if !ok {
		t.Fatalf("input not on screen:\n%s", screenText(m))
	}
	x := ansi.StringWidth(before)

	m = update(t, m, tea.MouseClickMsg{X: x, Y: area.Min.Y, Button: tea.MouseLeft})
	m = update(t, m, tea.MouseReleaseMsg{X: x, Y: area.Min.Y, Button: tea.MouseLeft})
	if m.input.HasSelection() || m.input.Column() != len("hello ") || m.focus != focusEditor {
		t.Fatalf("click: selection %v, column %d", m.input.HasSelection(), m.input.Column())
	}

	m = update(t, m, tea.MouseClickMsg{X: x, Y: area.Min.Y, Button: tea.MouseLeft})
	m = update(t, m, tea.MouseMotionMsg{X: x + 5, Y: area.Min.Y, Button: tea.MouseLeft})
	m = update(t, m, tea.MouseReleaseMsg{X: x + 5, Y: area.Min.Y, Button: tea.MouseLeft})
	if got := m.input.SelectedText(); got != "world" {
		t.Fatalf("selected %q", got)
	}
}

func TestResizeRerendersMessagesInBatches(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{Done: &Turn{Role: RoleAssistant, Text: "ok"}}}}
	m := newTestModel(p)
	for i := range 10 {
		m = sendMessage(t, m, fmt.Sprintf("message %d", i))
	}

	m, cmd := updateCmd(t, m, tea.WindowSizeMsg{Width: 90, Height: 30})
	if cmd == nil {
		t.Fatal("a width change didn't start re-rendering the messages")
	}
	if _, cmd := updateCmd(t, m, tea.WindowSizeMsg{Width: 90, Height: 20}); cmd != nil {
		t.Fatal("a height-only change re-rendered the messages")
	}

	stale := prewarmMsg{seq: m.chat.prewarmSeq}
	m, _ = updateCmd(t, m, tea.WindowSizeMsg{Width: 80, Height: 30})
	if _, cmd := updateCmd(t, m, stale); cmd != nil {
		t.Fatal("re-rendering for an earlier width carried on after another resize")
	}

	msg := tea.Msg(prewarmMsg{seq: m.chat.prewarmSeq})
	batches := 0
	for msg != nil {
		batches++
		m, cmd = updateCmd(t, m, msg)
		if cmd == nil {
			break
		}
		msg = cmd()
	}
	if want := (m.chat.Len() + prewarmBatch - 1) / prewarmBatch; batches != want {
		t.Fatalf("re-rendered %d messages in %d batches, want %d", m.chat.Len(), batches, want)
	}
}

func TestRenderersAreKeptForOneWidth(t *testing.T) {
	com := newCommon(newStyles(pantera()))
	for width := 40; width < 60; width++ { // dragging the window narrower
		com.renderer(rendererReply, width)
		com.renderer(rendererQuiet, width)
	}
	if len(com.renderers) != 2 {
		t.Fatalf("kept %d renderers after resizing, want 2", len(com.renderers))
	}
	first := com.renderer(rendererReply, 59)
	if again := com.renderer(rendererReply, 59); again != first {
		t.Fatal("renderer rebuilt at an unchanged width")
	}
}

func TestCodeBlocksUseThePalettesExactColours(t *testing.T) {
	p := pantera()
	com := newCommon(newStyles(p))
	out := renderMarkdown(com.styles, com.renderer(rendererReply, 60), "```go\nfunc main() {}\n```", 60)

	r, g, b, _ := p.Info.RGBA() // keywords
	keyword := fmt.Sprintf("38;2;%d;%d;%d", r>>8, g>>8, b>>8)
	if !strings.Contains(out, keyword) {
		t.Fatalf("keyword not in the palette's exact colour %s: %q", keyword, out)
	}
	r, g, b, _ = p.BgLessVisible.RGBA()
	if bg := fmt.Sprintf("48;2;%d;%d;%d", r>>8, g>>8, b>>8); strings.Contains(out, bg) {
		t.Fatalf("code tokens have a background: %q", out)
	}
	if got := ansi.Strip(out); !strings.Contains(got, "func main() {}") {
		t.Fatalf("code = %q", got)
	}
}

func TestModelPickerOffersListedModels(t *testing.T) {
	a := &fakeProvider{name: "Anthropic", model: "claude-opus-5-5"}
	m := newTestModel(a)
	m = update(t, m, modelsMsg{provider: 0, err: errors.New("no key")})
	m = update(t, m, ctrl('l'))
	if !strings.Contains(screenText(m), "claude-sonnet-5-5") {
		t.Fatalf("known models not offered after listing failed:\n%s", screenText(m))
	}
	m = update(t, m, keyEsc)

	m = update(t, m, modelsMsg{provider: 0, models: []ModelInfo{{ID: "claude-next"}, {ID: "claude-opus-5-5"}}})
	m = update(t, m, ctrl('l'))
	screen := screenText(m)
	if strings.Contains(screen, "claude-sonnet-5-5") || strings.Count(screen, "claude-opus-5-5") != 1 {
		t.Fatalf("picker should offer the current model once and the listed ones:\n%s", screen)
	}
	press(t, m, keyDown, keyEnter)
	if a.Model() != "claude-next" {
		t.Fatalf("model = %q", a.Model())
	}
}

func TestFormatTokens(t *testing.T) {
	for n, want := range map[int64]string{845: "845", 1000: "1K", 12400: "12.4K", 20000: "20K", 1_200_000: "1.2M", 2_000_000: "2M"} {
		if got := formatTokens(n); got != want {
			t.Errorf("formatTokens(%d) = %q, want %q", n, got, want)
		}
	}
}

func TestTokenUsageIsShown(t *testing.T) {
	p := &fakeProvider{name: "A", events: []StreamEvent{{
		Done:  &Turn{Role: RoleAssistant, Text: "Hello"},
		Usage: &Usage{Input: 12400, Output: 845, Context: 170000},
	}}}
	m := newModel([]Provider{p}, 0)
	m = update(t, m, tea.WindowSizeMsg{Width: 140, Height: 40})
	m = sendMessage(t, m, "hi")

	screen := screenText(m)
	if !strings.Contains(screen, "12.4K in · 845 out") {
		t.Fatalf("reply footer lacks its tokens:\n%s", screen)
	}
	if !strings.Contains(screen, "170K tokens") {
		t.Fatalf("sidebar lacks the context size when the window is unknown:\n%s", screen)
	}

	m = update(t, m, modelsMsg{provider: 0, models: []ModelInfo{{ID: "fake-model", ContextWindow: 200000}}})
	screen = screenText(m)
	if !strings.Contains(screen, contextWarnIcon+" 85% (170K)") {
		t.Fatalf("sidebar lacks the context share:\n%s", screen)
	}
	m = update(t, m, tea.WindowSizeMsg{Width: 100, Height: 40})
	header := strings.Split(screenText(m), "\n")[m.rects.header.Min.Y]
	if !strings.Contains(header, "◇ fake-model • 85%") {
		t.Fatalf("compact header lacks the context share: %q", header)
	}

	m = update(t, m, ctrl('n'))
	if m.usage != nil {
		t.Fatal("a new chat kept the old usage")
	}
}

func TestPasteGoesToTheOpenDialog(t *testing.T) {
	m := newModel([]Provider{&fakeProvider{name: "A"}}, 0)
	m = update(t, m, tea.WindowSizeMsg{Width: 140, Height: 40})
	m = update(t, m, ctrl('p'))
	m = update(t, m, tea.PasteMsg{Content: "quit"})
	if m.input.Value() != "" {
		t.Fatalf("paste reached the message box behind the dialog: %q", m.input.Value())
	}
	screen := screenText(m)
	if !strings.Contains(screen, "quit") || strings.Contains(screen, "New Chat") {
		t.Fatalf("paste didn't filter the palette:\n%s", screen)
	}

	m = update(t, m, keyEsc)
	m = update(t, m, tea.PasteMsg{Content: "hello"})
	if m.input.Value() != "hello" {
		t.Fatalf("paste without a dialog = %q", m.input.Value())
	}
}

// Windows clipboard text ends its lines with \r\n, which the textarea
// would otherwise turn into two line breaks.
func TestPastedCRLFIsOneLineBreak(t *testing.T) {
	m := newTestModel(&fakeProvider{name: "A"})
	m = update(t, m, tea.PasteMsg{Content: "a\r\nb\r\nc"})
	if m.input.Value() != "a\nb\nc" {
		t.Fatalf("input = %q", m.input.Value())
	}
}

func TestSwitchingModelClearsUsage(t *testing.T) {
	a := &fakeProvider{name: "A", events: []StreamEvent{{
		Done:  &Turn{Role: RoleAssistant, Text: "Hello"},
		Usage: &Usage{Context: 1000},
	}}}
	b := &fakeProvider{name: "B"}
	m := newModel([]Provider{a, b}, 0)
	m = update(t, m, tea.WindowSizeMsg{Width: 140, Height: 40})
	m = sendMessage(t, m, "hi")

	m.selectModel(modelChoice{provider: 0, model: a.Model()})
	if m.usage == nil {
		t.Fatal("re-selecting the same model dropped the usage")
	}
	m.selectModel(modelChoice{provider: 0, model: "other-model"})
	if m.usage != nil {
		t.Fatal("switching model kept the old usage")
	}

	m = sendMessage(t, m, "again")
	m.selectModel(modelChoice{provider: 1, model: b.Model()})
	if m.usage != nil {
		t.Fatal("switching provider kept the old usage")
	}
}

func TestSplitArgs(t *testing.T) {
	for in, want := range map[string][]string{
		"vim":                              {"vim"},
		"  code   --wait ":                 {"code", "--wait"},
		`"C:\Program Files\x\x.exe" -w`:    {`C:\Program Files\x\x.exe`, "-w"},
		`emacs -nw '--eval=(setq a 1)'`:    {"emacs", "-nw", "--eval=(setq a 1)"},
		`C:\Users\me\bin\ed.exe --flag=""`: {`C:\Users\me\bin\ed.exe`, "--flag="},
	} {
		if got := splitArgs(in); fmt.Sprint(got) != fmt.Sprint(want) || len(got) != len(want) {
			t.Errorf("splitArgs(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestEditorPathWithSpaces(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "Program Files")
	if err := os.Mkdir(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	editor := filepath.Join(dir, "edit.exe")
	if err := os.WriteFile(editor, nil, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("VISUAL", "")
	t.Setenv("EDITOR", editor)
	if got := editorCommand(); len(got) != 1 || got[0] != editor {
		t.Fatalf("editorCommand() = %q, want [%q]", got, editor)
	}
}

func TestFinishedReplyIsRenderedWhole(t *testing.T) {
	com := newCommon(newStyles(pantera()))
	a := newAssistantItem(com, "Anthropic", "m")
	const width = 80
	w := contentWidth(width)

	// One long paragraph broken only by single newlines, which the streaming
	// cache cuts at once it passes relaxBoundaryAfter.
	var b strings.Builder
	for i := 0; b.Len() < 3*relaxBoundaryAfter; i++ {
		fmt.Fprintf(&b, "Line %d of a long paragraph that wraps across the width.\n", i)
	}
	text := b.String()
	for i := 0; i < len(text); i += 200 {
		a.appendText(text[i:min(i+200, len(text))])
		a.RawRender(width)
	}
	whole := renderMarkdown(com.styles, com.renderer(rendererReply, w), text, w)
	if strings.Contains(a.RawRender(width), whole) {
		t.Fatal("the streamed render already matches the whole one; the test no longer covers a glued render")
	}

	a.complete(text, "", nil)
	for range 2 { // and again, once the render cache is warm
		if !strings.Contains(a.RawRender(width), whole) {
			t.Fatal("finished reply is not rendered whole")
		}
		a.SetFocused(!a.focused)
	}
}
