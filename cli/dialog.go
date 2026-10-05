package main

// The dialog overlay, frame and list dialogs are adapted from Crush
// (github.com/charmbracelet/crush, internal/ui/dialog), Copyright 2025-2026
// Charmbracelet, Inc., used under FSL-1.1-MIT.

import (
	"cmp"
	"fmt"
	"image"
	"slices"
	"strings"

	"charm.land/bubbles/v2/help"
	"charm.land/bubbles/v2/key"
	"charm.land/bubbles/v2/textinput"
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"
	uv "github.com/charmbracelet/ultraviolet"
	"github.com/charmbracelet/x/ansi"
	"github.com/rivo/uniseg"
	"github.com/sahilm/fuzzy"

	"chat/list"
)

// dialog is drawn on top of the UI and gets the keys while it is open.
type dialog interface {
	ID() string
	// HandleMsg processes a message and returns what the UI should do, if
	// anything: one of the action types below.
	HandleMsg(msg tea.Msg) action
	// Draw paints the dialog within area and returns where the cursor goes.
	Draw(scr uv.Screen, area uv.Rectangle) *tea.Cursor
}

type action any

type (
	actionClose        struct{}
	actionQuit         struct{}
	actionCmd          struct{ cmd tea.Cmd }
	actionSelectModel  struct{ choice modelChoice }
	actionSelectEffort struct{ effort string }
	actionRunCommand   struct{ command command }
)

var closeKey = key.NewBinding(key.WithKeys("esc", "alt+esc"), key.WithHelp("esc", "close"))

// overlay is the stack of open dialogs; the last one is in front.
type overlay struct {
	dialogs []dialog
}

func (o *overlay) HasDialogs() bool { return len(o.dialogs) > 0 }

func (o *overlay) Open(d dialog) { o.dialogs = append(o.dialogs, d) }

func (o *overlay) Contains(id string) bool {
	for _, d := range o.dialogs {
		if d.ID() == id {
			return true
		}
	}
	return false
}

func (o *overlay) Front() dialog {
	if len(o.dialogs) == 0 {
		return nil
	}
	return o.dialogs[len(o.dialogs)-1]
}

func (o *overlay) CloseFront() {
	if len(o.dialogs) > 0 {
		o.dialogs = o.dialogs[:len(o.dialogs)-1]
	}
}

// Update passes msg to the front dialog.
func (o *overlay) Update(msg tea.Msg) action {
	if d := o.Front(); d != nil {
		return d.HandleMsg(msg)
	}
	return nil
}

// Draw paints every dialog, back to front, and returns the front one's
// cursor.
func (o *overlay) Draw(scr uv.Screen, area uv.Rectangle) *tea.Cursor {
	var cur *tea.Cursor
	for _, d := range o.dialogs {
		cur = d.Draw(scr, area)
	}
	return cur
}

// drawCentered draws view in the middle of area, moving cur along with it.
func drawCentered(scr uv.Screen, area uv.Rectangle, view string, cur *tea.Cursor) {
	width, height := lipgloss.Size(view)
	width, height = min(width, area.Dx()), min(height, area.Dy())
	x := area.Min.X + (area.Dx()-width)/2
	y := area.Min.Y + (area.Dy()-height)/2
	if cur != nil {
		cur.X += x
		cur.Y += y
	}
	uv.NewStyledString(view).Draw(scr, image.Rect(x, y, x+width, y+height))
}

// dialogTitle is the title followed by a gradient of diagonals out to width.
func dialogTitle(st *Styles, title string, width int) string {
	if lipgloss.Width(title) > width {
		return ansi.Truncate(title, width, "…")
	}
	if rest := width - lipgloss.Width(title) - 1; rest > 0 {
		title += " " + gradientText(rep(diag, rest), st.Primary, st.Secondary, false)
	}
	return title
}

// renderDialog frames a dialog: the title, the parts, and the help line.
func renderDialog(st *Styles, width int, title string, parts []string, helpLine string) string {
	view := st.Dialog.View.Width(width)
	inner := width - view.GetHorizontalFrameSize() - st.Dialog.Title.GetHorizontalFrameSize()
	all := append([]string{st.Dialog.Title.Render(dialogTitle(st, title, inner))}, parts...)
	if helpLine != "" {
		all = append(all, helpLine)
	}
	return view.Render(strings.Join(all, "\n"))
}

// dialogHelp renders key hints as one line that fits contentWidth, ending
// with an ellipsis rather than wrapping when they don't all fit.
func dialogHelp(st *Styles, h *help.Model, bindings []key.Binding, contentWidth int) string {
	width := max(0, contentWidth-st.Dialog.HelpView.GetHorizontalFrameSize())
	sep := h.Styles.ShortSeparator.Inline(true).Render(h.ShortSeparator)
	ellipsis := h.Styles.Ellipsis.Inline(true).Render(cmp.Or(h.Ellipsis, "…"))

	var b strings.Builder
	total := 0
	for _, kb := range bindings {
		seg := ""
		if total > 0 {
			seg = sep
		}
		seg += h.Styles.ShortKey.Inline(true).Render(kb.Help().Key) + " " +
			h.Styles.ShortDesc.Inline(true).Render(kb.Help().Desc)
		if w := lipgloss.Width(seg); total+w <= width {
			total += w
			b.WriteString(seg)
			continue
		}
		tail := ellipsis
		if total > 0 {
			tail = " " + ellipsis
		}
		if total+lipgloss.Width(tail) <= width {
			b.WriteString(tail)
		}
		break
	}
	return st.Dialog.HelpView.Render(b.String())
}

// pickerItem is one row of a picker: a title, and info on the right such as
// a key or the provider.
type pickerItem struct {
	*list.Versioned
	st      *Styles
	title   string
	info    string
	value   any
	focused bool
	match   fuzzy.Match
}

func newPickerItem(st *Styles, title, info string, value any) *pickerItem {
	return &pickerItem{Versioned: list.NewVersioned(), st: st, title: title, info: info, value: value}
}

func (p *pickerItem) Filter() string { return p.title }

func (p *pickerItem) SetFocused(focused bool) {
	if p.focused != focused {
		p.focused = focused
		p.Bump()
	}
}

func (p *pickerItem) SetMatch(m fuzzy.Match) {
	if p.match.Str == m.Str && p.match.Score == m.Score && slices.Equal(p.match.MatchedIndexes, m.MatchedIndexes) {
		return
	}
	p.match = m
	p.Bump()
}

// Render draws the row full width, the matched characters underlined and
// the info right-aligned.
func (p *pickerItem) Render(width int) string {
	style, infoStyle := p.st.Dialog.NormalItem, p.st.Dialog.InfoBlurred
	if p.focused {
		style, infoStyle = p.st.Dialog.SelectedItem, p.st.Dialog.InfoFocused
	}
	lineWidth := max(0, width-style.GetHorizontalFrameSize())

	var info string
	if p.info != "" {
		text := p.info
		if maxInfo := lineWidth / 2; lipgloss.Width(text)+2 > maxInfo {
			text = ansi.Truncate(text, max(0, maxInfo-2), "…")
		}
		info = infoStyle.Render(" " + text + " ")
	}
	title := ansi.Truncate(p.title, max(0, lineWidth-lipgloss.Width(info)), "…")
	gap := strings.Repeat(" ", max(0, lineWidth-lipgloss.Width(title)-lipgloss.Width(info)))
	return style.Render(underlineMatches(title, p.match.MatchedIndexes) + gap + info)
}

// underlineMatches underlines the characters of s at the given byte
// offsets, as fuzzy reports them.
func underlineMatches(s string, matched []int) string {
	if len(matched) == 0 {
		return s
	}
	set := make(map[int]bool, len(matched))
	for _, i := range matched {
		set[i] = true
	}
	var b strings.Builder
	on := false
	gr := uniseg.NewGraphemes(s)
	for gr.Next() {
		start, _ := gr.Positions()
		if set[start] != on {
			on = !on
			b.WriteString(ansi.NewStyle().Underline(on).String())
		}
		b.WriteString(gr.Str())
	}
	if on {
		b.WriteString(ansi.NewStyle().Underline(false).String())
	}
	return b.String()
}

// pickerDialog is a filterable list to choose from: the command palette,
// the model picker and the reasoning effort picker.
type pickerDialog struct {
	com       *common
	id, title string
	maxWidth  int
	maxHeight int
	list      *list.FilterableList
	input     textinput.Model
	help      help.Model
	choose    func(value any) action

	keys struct {
		Select, Next, Previous, UpDown, Close key.Binding
	}
}

func newPickerDialog(com *common, id, title string, items []*pickerItem, selected int, choose func(any) action) *pickerDialog {
	d := &pickerDialog{com: com, id: id, title: title, maxWidth: 60, maxHeight: 20, choose: choose}

	filterable := make([]list.FilterableItem, len(items))
	for i, it := range items {
		filterable[i] = it
	}
	d.list = list.NewFilterableList(filterable...)
	d.list.Focus()
	d.list.SetSelected(selected)

	d.input = textinput.New()
	d.input.SetVirtualCursor(false)
	d.input.Placeholder = "Type to filter"
	d.input.SetStyles(com.styles.TextInput)
	d.input.Focus()

	d.help = help.New()
	d.help.Styles = com.styles.Help

	d.keys.Select = key.NewBinding(key.WithKeys("enter", "ctrl+y"), key.WithHelp("enter", "confirm"))
	d.keys.Next = key.NewBinding(key.WithKeys("down", "ctrl+n"), key.WithHelp("↓", "next item"))
	d.keys.Previous = key.NewBinding(key.WithKeys("up", "ctrl+p"), key.WithHelp("↑", "previous item"))
	d.keys.UpDown = key.NewBinding(key.WithKeys("up", "down"), key.WithHelp("↑/↓", "choose"))
	d.keys.Close = closeKey
	return d
}

func (d *pickerDialog) ID() string { return d.id }

func (d *pickerDialog) HandleMsg(msg tea.Msg) action {
	if _, ok := msg.(tea.PasteMsg); ok {
		return d.updateFilter(msg)
	}
	keyMsg, ok := msg.(tea.KeyPressMsg)
	if !ok {
		return nil
	}
	switch {
	case key.Matches(keyMsg, d.keys.Close):
		return actionClose{}
	case key.Matches(keyMsg, d.keys.Previous):
		if d.list.IsSelectedFirst() {
			d.list.SelectLast()
		} else {
			d.list.SelectPrev()
		}
		d.list.ScrollToSelected()
	case key.Matches(keyMsg, d.keys.Next):
		if d.list.IsSelectedLast() {
			d.list.SelectFirst()
		} else {
			d.list.SelectNext()
		}
		d.list.ScrollToSelected()
	case key.Matches(keyMsg, d.keys.Select):
		if it, ok := d.list.SelectedItem().(*pickerItem); ok {
			return d.choose(it.value)
		}
	default:
		return d.updateFilter(msg)
	}
	return nil
}

// updateFilter passes msg to the filter input and refilters the list if
// the text changed.
func (d *pickerDialog) updateFilter(msg tea.Msg) action {
	prev := d.input.Value()
	var cmd tea.Cmd
	d.input, cmd = d.input.Update(msg)
	if d.input.Value() != prev {
		d.list.SetFilter(d.input.Value())
		d.list.SetSelected(0)
	}
	return actionCmd{cmd}
}

func (d *pickerDialog) Draw(scr uv.Screen, area uv.Rectangle) *tea.Cursor {
	st := d.com.styles
	view := st.Dialog.View
	width := max(0, min(d.maxWidth, area.Dx()-view.GetHorizontalBorderSize()))
	inner := width - view.GetHorizontalFrameSize()
	d.input.SetWidth(max(0, inner-st.Dialog.InputPrompt.GetHorizontalFrameSize()-lipgloss.Width(d.input.Prompt)-1))

	// Size the list to its items, within the dialog's limits.
	chrome := st.Dialog.Title.GetVerticalFrameSize() + 1 +
		st.Dialog.InputPrompt.GetVerticalFrameSize() + 1 +
		st.Dialog.List.GetVerticalFrameSize() +
		st.Dialog.HelpView.GetVerticalFrameSize() + 1 +
		view.GetVerticalFrameSize()
	d.list.SetSize(inner, 1)
	total := d.list.TotalHeight()
	listHeight := max(1, min(total, d.maxHeight-chrome, area.Dy()-chrome))
	listWidth := inner
	if total > listHeight {
		listWidth-- // room for the scrollbar
	}
	d.list.SetSize(listWidth, listHeight)
	d.list.ScrollToSelected()

	listView := d.list.Render()
	if bar := scrollbar(st, listHeight, total, listHeight, d.list.Offset()); bar != "" {
		listView = lipgloss.JoinHorizontal(lipgloss.Top, lipgloss.NewStyle().Width(listWidth).Height(listHeight).Render(listView), bar)
	}

	out := renderDialog(st, width, d.title, []string{
		st.Dialog.InputPrompt.Render(d.input.View()),
		st.Dialog.List.Height(listHeight).Render(listView),
	}, dialogHelp(st, &d.help, []key.Binding{d.keys.UpDown, d.keys.Select, d.keys.Close}, inner))

	cur := d.input.Cursor()
	if cur != nil {
		// Past the dialog's border and title, and the input's margin.
		cur.X += view.GetBorderLeftSize() + view.GetPaddingLeft() + st.Dialog.InputPrompt.GetMarginLeft()
		cur.Y += view.GetBorderTopSize() + view.GetPaddingTop() + st.Dialog.Title.GetVerticalFrameSize() + 1 +
			st.Dialog.InputPrompt.GetMarginTop()
	}
	drawCentered(scr, area, out, cur)
	return cur
}

// command is an entry in the command palette.
type command int

const (
	commandModels command = iota
	commandEffort
	commandNewChat
	commandOpenEditor
	commandCopyReply
	commandHelp
	commandQuit
)

func newCommandsDialog(com *common, keys keyMap) *pickerDialog {
	st := com.styles
	items := []*pickerItem{
		newPickerItem(st, "Switch Model", keys.Models.Help().Key, commandModels),
		newPickerItem(st, "Select Reasoning Effort", "", commandEffort),
		newPickerItem(st, "New Chat", keys.NewChat.Help().Key, commandNewChat),
		newPickerItem(st, "Open External Editor", keys.Editor.OpenEditor.Help().Key, commandOpenEditor),
		newPickerItem(st, "Copy Last Reply", "", commandCopyReply),
		newPickerItem(st, "Toggle Help", keys.Help.Help().Key, commandHelp),
		newPickerItem(st, "Quit", keys.Quit.Help().Key, commandQuit),
	}
	return newPickerDialog(com, "commands", "Commands", items, 0, func(v any) action {
		return actionRunCommand{v.(command)}
	})
}

// modelChoice is a model the picker offers, and the provider serving it.
type modelChoice struct {
	provider int
	model    string
}

// knownModels lists models the picker offers for each provider, besides
// the one it was started with, until the provider has listed its own.
var knownModels = map[string][]string{
	"Anthropic": {"claude-opus-5-5", "claude-sonnet-5-5"},
	"OpenAI":    {"gpt-6-astra", "gpt-6.1-sol"},
}

// newModelsDialog offers each provider's current model, then the models it
// listed (catalog, by provider) or else the ones known here.
func newModelsDialog(com *common, providers []Provider, catalog [][]ModelInfo, active int) *pickerDialog {
	var items []*pickerItem
	selected := 0
	for i, p := range providers {
		others := knownModels[p.Name()]
		if len(catalog[i]) > 0 {
			others = nil
			for _, info := range catalog[i] {
				others = append(others, info.ID)
			}
		}
		models := []string{p.Model()}
		for _, m := range others {
			if m != p.Model() {
				models = append(models, m)
			}
		}
		for j, m := range models {
			info := p.Name()
			if i == active && j == 0 {
				info = "current · " + info
				selected = len(items)
			}
			items = append(items, newPickerItem(com.styles, m, info, modelChoice{provider: i, model: m}))
		}
	}
	return newPickerDialog(com, "models", "Switch Model", items, selected, func(v any) action {
		return actionSelectModel{v.(modelChoice)}
	})
}

// newEffortDialog picks the reasoning effort of one provider.
func newEffortDialog(com *common, provider, current string) *pickerDialog {
	items := make([]*pickerItem, len(efforts))
	selected := 0
	for i, e := range efforts {
		info := ""
		if e == current {
			info, selected = "current", i
		}
		items[i] = newPickerItem(com.styles, formatEffort(e), info, e)
	}
	d := newPickerDialog(com, "effort", "Reasoning Effort · "+provider, items, selected, func(v any) action {
		return actionSelectEffort{v.(string)}
	})
	d.maxWidth = 50
	return d
}

// quitDialog asks before quitting.
type quitDialog struct {
	com        *common
	selectedNo bool
	keys       struct {
		LeftRight, EnterSpace, Yes, No, Tab, Close, Quit key.Binding
	}
}

func newQuitDialog(com *common) *quitDialog {
	q := &quitDialog{com: com, selectedNo: true}
	q.keys.LeftRight = key.NewBinding(key.WithKeys("left", "right"), key.WithHelp("←/→", "switch options"))
	q.keys.EnterSpace = key.NewBinding(key.WithKeys("enter", " ", "space"), key.WithHelp("enter/space", "confirm"))
	q.keys.Yes = key.NewBinding(key.WithKeys("y", "Y"), key.WithHelp("y", "yes"))
	q.keys.No = key.NewBinding(key.WithKeys("n", "N"), key.WithHelp("n", "no"))
	q.keys.Tab = key.NewBinding(key.WithKeys("tab"), key.WithHelp("tab", "switch options"))
	q.keys.Close = closeKey
	q.keys.Quit = key.NewBinding(key.WithKeys("ctrl+c"), key.WithHelp("ctrl+c", "quit"))
	return q
}

func (q *quitDialog) ID() string { return "quit" }

func (q *quitDialog) HandleMsg(msg tea.Msg) action {
	keyMsg, ok := msg.(tea.KeyPressMsg)
	if !ok {
		return nil
	}
	switch {
	case key.Matches(keyMsg, q.keys.Quit, q.keys.Yes):
		return actionQuit{}
	case key.Matches(keyMsg, q.keys.Close, q.keys.No):
		return actionClose{}
	case key.Matches(keyMsg, q.keys.LeftRight, q.keys.Tab):
		q.selectedNo = !q.selectedNo
	case key.Matches(keyMsg, q.keys.EnterSpace):
		if q.selectedNo {
			return actionClose{}
		}
		return actionQuit{}
	}
	return nil
}

func (q *quitDialog) Draw(scr uv.Screen, area uv.Rectangle) *tea.Cursor {
	st := q.com.styles
	button := func(text string, selected bool) string {
		style := st.Dialog.ButtonBlurred
		if selected {
			style = st.Dialog.ButtonFocused
		}
		return style.Padding(0, 3).Render(text)
	}
	content := st.Dialog.QuitContent.Render(lipgloss.JoinVertical(lipgloss.Center,
		"Are you sure you want to quit?",
		"",
		button("Yep!", !q.selectedNo)+" "+button("Nope", q.selectedNo),
		"",
		st.Dialog.QuitHint.Render("To quit without confirmation"),
		st.Dialog.QuitHint.Render(fmt.Sprintf("press %s twice.", q.keys.Quit.Help().Key)),
	))
	frame := st.Dialog.QuitFrame
	if area.Dx()-frame.GetHorizontalBorderSize() < lipgloss.Width(content) {
		frame = frame.Padding(1, 0)
	}
	drawCentered(scr, area, frame.Render(content), nil)
	return nil
}
