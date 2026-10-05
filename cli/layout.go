package main

// The screen layout, sidebar, header and help bar follow Crush's
// (github.com/charmbracelet/crush, internal/ui/model), Copyright 2025-2026
// Charmbracelet, Inc., used under FSL-1.1-MIT.

import (
	"context"
	"fmt"
	"image"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"charm.land/bubbles/v2/key"
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"
	"github.com/charmbracelet/x/ansi"
)

const (
	sidebarWidth = 32
	// Below either size the sidebar gives way to a one-line header.
	compactWidth  = 120
	compactHeight = 30
	// A sidebar shorter than this shows the one-line logo.
	sidebarLogoMinHeight = 30
	landingHeaderHeight  = 4

	// Past this share of the context window, a warning sign shows by it.
	contextWarnPercent = 80
	contextWarnIcon    = "⚠"
)

// uiLayout holds where each part of the screen is drawn. Parts that aren't
// shown in the current state are empty.
type uiLayout struct {
	header  image.Rectangle
	main    image.Rectangle // the conversation, or the landing page
	editor  image.Rectangle // the input, with a blank row above and below it
	sidebar image.Rectangle
	help    image.Rectangle
}

// landing reports whether the start screen is shown: nothing has been sent.
func (m *model) landing() bool { return m.chat.Len() == 0 }

func (m *model) compact() bool { return m.width < compactWidth || m.height < compactHeight }

// generateLayout splits the screen as Crush does:
//
//	landing        chat              chat (compact)
//	header         main  | sidebar   header
//	main           ------|           main
//	editor         editor|           editor
//	help           help              help
func (m *model) generateLayout() uiLayout {
	w, h := m.width, m.height
	editorHeight := m.input.Height() + 2
	helpHeight := m.helpHeight()

	// One blank cell around the app; the help sits below it with a blank
	// row underneath.
	app := image.Rect(1, 1, w-1, h-helpHeight-1)
	l := uiLayout{help: image.Rect(0, h-helpHeight-1, w, h)}

	switch {
	case m.landing():
		app.Min.X++
		app.Max.X--
		l.header = image.Rect(app.Min.X, app.Min.Y, app.Max.X, app.Min.Y+landingHeaderHeight)
		l.main = image.Rect(app.Min.X, l.header.Max.Y, app.Max.X, app.Max.Y-editorHeight)
		l.editor = image.Rect(app.Min.X-1, l.main.Max.Y, app.Max.X+1, app.Max.Y)

	case m.compact():
		l.header = image.Rect(app.Min.X, app.Min.Y, app.Max.X, app.Min.Y+1)
		l.editor = image.Rect(app.Min.X, app.Max.Y-editorHeight, app.Max.X, app.Max.Y)
		l.main = image.Rect(app.Min.X, l.header.Max.Y+1, app.Max.X-1, l.editor.Min.Y-1)

	default:
		mainRight := app.Max.X - sidebarWidth
		l.sidebar = image.Rect(mainRight+1, app.Min.Y, app.Max.X, app.Max.Y)
		l.editor = image.Rect(app.Min.X, app.Max.Y-editorHeight, mainRight, app.Max.Y)
		l.main = image.Rect(app.Min.X, app.Min.Y, mainRight-1, l.editor.Min.Y-1)
	}

	// On a tiny terminal the parts can run out of room; keep them non-negative.
	for _, r := range []*image.Rectangle{&l.header, &l.main, &l.editor, &l.sidebar, &l.help} {
		r.Max.X = max(r.Max.X, r.Min.X)
		r.Max.Y = max(r.Max.Y, r.Min.Y)
	}
	return l
}

// section is a heading followed by a rule out to width: "Title ─────".
func section(st *Styles, text string, width int) string {
	if rest := width - lipgloss.Width(text) - 1; rest > 0 {
		text += " " + st.SectionLine.Render(strings.Repeat(sectionSeparator, rest))
	}
	return text
}

// modelInfo is "◇ model via Provider" and the reasoning effort, wrapping the
// provider onto its own line when it doesn't fit.
func (m *model) modelInfo(width int) string {
	st := m.com.styles
	p := m.providers[m.active]
	icon := st.Subtle.Render(modelIcon)
	lines := []string{icon + " " + st.Base.Render(p.Model()) + " " + st.Muted.Render("via "+p.Name())}
	if lipgloss.Width(lines[0]) > width {
		lines = []string{icon + " " + st.Base.Render(p.Model()), st.Muted.PaddingLeft(2).Render("via " + p.Name())}
	}
	if effort := m.activeEffort(); effort != "" {
		lines = append(lines, st.Subtle.PaddingLeft(2).Render("Reasoning "+formatEffort(effort)))
	}
	if m.usage != nil {
		lines = append(lines, lipgloss.NewStyle().PaddingLeft(2).Render(m.contextUsage()))
	}
	return lipgloss.NewStyle().Width(width).Render(strings.Join(lines, "\n"))
}

// activeEffort is the reasoning effort the active model will be sent, which
// is lower than the one chosen when the model doesn't support that.
func (m *model) activeEffort() string {
	if r, ok := m.providers[m.active].(effortReporter); ok {
		return r.Effort()
	}
	return m.effort[m.active]
}

// contextWindow is the current model's context window, if its provider
// reported it.
func (m *model) contextWindow() int64 {
	model := m.providers[m.active].Model()
	for _, info := range m.catalog[m.active] {
		if info.ID == model {
			return info.ContextWindow
		}
	}
	return 0
}

// contextPercent is how full the context window is after the last reply,
// or -1 if that isn't known.
func (m *model) contextPercent() int {
	window := m.contextWindow()
	if m.usage == nil || window <= 0 {
		return -1
	}
	return int(m.usage.Context * 100 / window)
}

// contextUsage is how much of the context window the conversation fills,
// as Crush shows it: "12% (24.5K)", with a warning sign past 80%. Without a
// known window it is just the token count.
func (m *model) contextUsage() string {
	st := m.com.styles
	tokens := formatTokens(m.usage.Context)
	pct := m.contextPercent()
	if pct < 0 {
		return st.Subtle.Render(tokens + " tokens")
	}
	s := st.Muted.Render(fmt.Sprintf("%d%%", pct)) + " " + st.Subtle.Render("("+tokens+")")
	if pct > contextWarnPercent {
		s = lipgloss.NewStyle().Foreground(st.Warning).Render(contextWarnIcon) + " " + s
	}
	return s
}

func formatEffort(effort string) string {
	if effort == "xhigh" {
		return "X-High"
	}
	return strings.ToUpper(effort[:1]) + effort[1:]
}

// providerList lists the providers, with a green dot on the active one.
func (m *model) providerList(width int) string {
	st := m.com.styles
	var rows []string
	for i, p := range m.providers {
		icon := st.ProviderOffline.Render()
		if i == m.active {
			icon = st.ProviderOnline.Render()
		}
		name := st.Muted.Render(p.Name())
		desc := ansi.Truncate(p.Model(), width-lipgloss.Width(icon)-lipgloss.Width(name)-2, "…")
		rows = append(rows, icon+" "+name+" "+st.Subtle.Render(desc))
	}
	return strings.Join(rows, "\n")
}

// shortDir is the working directory with the home directory shortened to "~".
func (m *model) shortDir() string {
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		if rel, err := filepath.Rel(home, m.cwd); err == nil && !strings.HasPrefix(rel, "..") {
			return filepath.Join("~", rel)
		}
	}
	return m.cwd
}

// workingDir is the working directory, and the git branch when there is one.
func (m *model) workingDir(width int, inline bool) string {
	dir := m.shortDir()
	style := m.com.styles.Muted.Width(width)
	switch {
	case m.gitBranch == "":
		return style.Render(dir)
	case inline:
		return style.Render(ansi.Truncate(m.gitBranch+" • "+dir, width, "…"))
	default:
		return style.Render(m.gitBranch) + "\n" + style.Render(dir)
	}
}

// sessionTitle stands in for Crush's generated session title: the start of
// the first message.
func (m *model) sessionTitle(width int) string {
	title := strings.TrimSpace(strings.SplitN(m.chat.item(0).Text(), "\n", 2)[0])
	return m.com.styles.Muted.Width(width).MaxHeight(2).Render(title)
}

func (m *model) sidebarView(width, height int) string {
	st := m.com.styles
	contentWidth := max(width-2, 1)
	logo := sidebarLogo(st) + "\n"
	if height < sidebarLogoMinHeight {
		logo = smallLogo(st, contentWidth) + "\n"
	}
	content := strings.Join([]string{
		logo,
		m.sessionTitle(contentWidth),
		"",
		m.workingDir(contentWidth, false),
		"",
		m.modelInfo(contentWidth),
		"",
		section(st, st.Subtle.Render("Providers"), contentWidth),
		"",
		m.providerList(contentWidth),
	}, "\n")
	return lipgloss.NewStyle().MaxWidth(contentWidth).MaxHeight(height).Render(content)
}

// compactHeader replaces the sidebar on small screens:
// "CHAT ╱╱╱╱╱ branch • ~/dir • ◇ model".
func (m *model) compactHeader(width int) string {
	const padding = 1
	st := m.com.styles
	sep := st.Subtle.Render(" • ")
	details := st.Muted.Render(trimDir(m.shortDir()))
	if m.gitBranch != "" {
		details = lipgloss.NewStyle().Foreground(st.Secondary).Render(m.gitBranch) + sep + details
	}
	details += sep + st.Subtle.Render(modelIcon) + " " + st.Muted.Render(m.providers[m.active].Model())
	if pct := m.contextPercent(); pct >= 0 {
		details += sep + st.Muted.Render(fmt.Sprintf("%d%%", pct))
	}

	logo := gradientText(logoName, st.Secondary, st.Primary, true)
	avail := width - 2*padding - lipgloss.Width(logo) - 1
	details = ansi.Truncate(details, max(avail-4, 0), "…") // at least 3 diagonals and a space
	diagonals := max(3, avail-lipgloss.Width(details)-1)
	line := logo + " " + lipgloss.NewStyle().Foreground(st.Primary).Render(rep(diag, diagonals)) + " " + details
	return lipgloss.NewStyle().Padding(0, padding).Render(ansi.Truncate(line, width-2*padding, ""))
}

// trimDir keeps the last three parts of a long path.
func trimDir(dir string) string {
	parts := strings.Split(filepath.ToSlash(dir), "/")
	if len(parts) > 4 {
		parts = append([]string{"…"}, parts[len(parts)-3:]...)
	}
	return filepath.FromSlash(strings.Join(parts, "/"))
}

// landingView is the start screen below the logo: where you are, the model,
// and the providers.
func (m *model) landingView(width, height int) string {
	listWidth := min(40, width)
	return lipgloss.NewStyle().Width(width).MaxHeight(height).PaddingTop(1).Render(strings.Join([]string{
		m.workingDir(width, true),
		"",
		m.modelInfo(width),
		"",
		m.com.styles.Subtle.Render("Providers"),
		"",
		m.providerList(listWidth),
	}, "\n"))
}

// helpHeight is the rows the help takes, not counting the blank row below.
func (m *model) helpHeight() int {
	if !m.fullHelp {
		return 1
	}
	return lipgloss.Height(m.help.FullHelpView(m.fullHelpBindings()))
}

// shortHelpBindings are the keys shown along the bottom, which depend on
// what has focus.
func (m *model) shortHelpBindings() []key.Binding {
	k := m.keys
	var b []key.Binding
	if m.streaming {
		cancel := k.Cancel
		if m.canceling {
			cancel.SetHelp("esc", "press again to cancel")
		}
		b = append(b, cancel)
	}
	if m.focus == focusChat {
		b = append(b, k.Chat.UpDown, k.Chat.UpDownOneItem, k.Chat.Expand, k.Chat.Copy, k.Tab)
	} else {
		if !m.streaming {
			b = append(b, k.Editor.Send, k.Editor.Newline)
		}
		b = append(b, k.Commands, k.Models)
		if !m.landing() {
			b = append(b, k.Tab)
		}
	}
	return append(b, k.Help, k.Quit)
}

// fullHelpBindings are every key, in columns, shown by ctrl+g.
func (m *model) fullHelpBindings() [][]key.Binding {
	k := m.keys
	e, c := k.Editor, k.Chat
	return [][]key.Binding{
		{k.Commands, k.Models, k.NewChat, k.Tab, k.Cancel, k.EndFollow, k.Help, k.Quit},
		{e.Send, e.Newline, e.OpenEditor, e.History, e.Scroll},
		{e.SelectAll, e.CopySelection, e.CutSelection, e.PasteText},
		{c.UpDown, c.UpDownOneItem, c.PageUp, c.PageDown, c.HalfPageUp, c.HalfPageDown, c.Home, c.End},
		{c.Expand, c.Copy, c.ClearHighlight},
	}
}

// helpView is the key help along the bottom, or the current error, warning
// or info drawn over its first line.
func (m *model) helpView(width int) string {
	m.help.SetWidth(max(width-2, 0))
	var view string
	if m.fullHelp {
		view = prefixLines(m.help.FullHelpView(m.fullHelpBindings()), " ")
	} else {
		view = " " + m.help.ShortHelpView(m.shortHelpBindings())
	}
	if m.status == "" {
		return view
	}

	st := m.com.styles
	indicator, message := st.Status.ErrorIndicator, st.Status.ErrorMessage
	switch m.statusKind {
	case statusWarn:
		indicator, message = st.Status.WarnIndicator, st.Status.WarnMessage
	case statusInfo:
		indicator, message = st.Status.InfoIndicator, st.Status.InfoMessage
	}
	ind := indicator.Render()
	avail := max(0, width-lipgloss.Width(ind)-2)
	text := ansi.Truncate(strings.ReplaceAll(m.status, "\n", " "), avail, "…")
	text += strings.Repeat(" ", max(0, avail-lipgloss.Width(text)))
	lines := strings.Split(view, "\n")
	lines[0] = ind + message.Render(text)
	return strings.Join(lines, "\n")
}

type gitBranchMsg string

// fetchGitBranch looks up the branch shown beside the working directory.
func fetchGitBranch() tea.Msg {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "git", "rev-parse", "--abbrev-ref", "HEAD").Output()
	if err != nil {
		return gitBranchMsg("")
	}
	return gitBranchMsg(strings.TrimSpace(string(out)))
}
