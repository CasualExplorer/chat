package main

// Prompt history navigation follows Crush's (github.com/charmbracelet/crush,
// internal/ui/model/history.go), Copyright 2025-2026 Charmbracelet, Inc.,
// used under FSL-1.1-MIT.

import (
	"os"
	"os/exec"
	"runtime"
	"strings"

	tea "charm.land/bubbletea/v2"
)

// promptHistory is the messages sent this session, newest first, which Up
// and Down step through in the input.
type promptHistory struct {
	messages []string
	index    int    // the message shown, or -1 for the draft
	draft    string // what was typed before stepping into the history
}

func (h *promptHistory) add(text string) {
	if len(h.messages) == 0 || h.messages[0] != text {
		h.messages = append([]string{text}, h.messages...)
	}
	h.index, h.draft = -1, ""
}

// historyUp shows the previous message once the cursor is at the very start
// of the input; on the first line it moves there first.
func (m *model) historyUp(msg tea.Msg) tea.Cmd {
	if m.input.Length() == 0 || m.atInputStart() {
		if m.historyPrev() {
			return nil
		}
	}
	if m.input.Line() == 0 {
		m.input.CursorStart()
		return nil
	}
	return m.updateInput(msg)
}

// historyDown shows the next message once the cursor is at the very end of
// the input; on the last line it moves there first.
func (m *model) historyDown(msg tea.Msg) tea.Cmd {
	if m.atInputEnd() && m.historyNext() {
		return nil
	}
	if m.input.Line() == max(m.input.LineCount()-1, 0) {
		m.input.MoveToEnd()
		return nil
	}
	return m.updateInput(msg)
}

// historyEscape goes back to the draft, if a past message is shown.
func (m *model) historyEscape() bool {
	if m.prompts.index < 0 {
		return false
	}
	m.prompts.index = -1
	m.setInput(m.prompts.draft)
	return true
}

func (m *model) historyPrev() bool {
	h := &m.prompts
	if h.index+1 >= len(h.messages) {
		return false
	}
	if h.index == -1 {
		h.draft = m.input.Value()
	}
	h.index++
	m.setInput(h.messages[h.index])
	m.input.MoveToBegin()
	return true
}

func (m *model) historyNext() bool {
	h := &m.prompts
	if h.index < 0 {
		return false
	}
	h.index--
	if h.index < 0 {
		m.setInput(h.draft)
	} else {
		m.setInput(h.messages[h.index])
	}
	return true
}

// setInput replaces the input's text, leaving the cursor at the end.
func (m *model) setInput(text string) {
	m.input.Reset()
	m.input.InsertString(text)
	m.layout()
}

func (m *model) atInputStart() bool {
	return m.input.Line() == 0 && m.input.LineInfo().ColumnOffset == 0 && m.input.LineInfo().RowOffset == 0
}

func (m *model) atInputEnd() bool {
	lines := m.input.LineCount()
	if lines == 0 {
		return true
	}
	if m.input.Line() != lines-1 {
		return false
	}
	info := m.input.LineInfo()
	return info.RowOffset == info.Height-1 && (info.CharOffset >= info.CharWidth-1 || info.CharWidth == 0)
}

type editorDoneMsg struct {
	text string
	err  error
}

// openEditor hands the input to $VISUAL or $EDITOR in a temporary file and
// puts back what was saved.
func openEditor(text string) tea.Cmd {
	f, err := os.CreateTemp("", "chat-*.md")
	if err != nil {
		return func() tea.Msg { return editorDoneMsg{err: err} }
	}
	_, err = f.WriteString(text)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(f.Name())
		return func() tea.Msg { return editorDoneMsg{err: err} }
	}

	args := editorCommand()
	cmd := exec.Command(args[0], append(args[1:], f.Name())...)
	return tea.ExecProcess(cmd, func(err error) tea.Msg {
		defer os.Remove(f.Name())
		if err != nil {
			return editorDoneMsg{err: err}
		}
		data, err := os.ReadFile(f.Name())
		if err != nil {
			return editorDoneMsg{err: err}
		}
		text := strings.ReplaceAll(string(data), "\r\n", "\n")
		return editorDoneMsg{text: strings.TrimRight(text, "\n")}
	})
}

// editorCommand is the editor to run, with its arguments.
func editorCommand() []string {
	for _, env := range []string{"VISUAL", "EDITOR"} {
		value := strings.TrimSpace(os.Getenv(env))
		if value == "" {
			continue
		}
		// An unquoted path with spaces, such as C:\Program Files\...\x.exe.
		if strings.ContainsAny(value, " \t") && !strings.ContainsAny(value, `"'`) {
			if _, err := exec.LookPath(value); err == nil {
				return []string{value}
			}
		}
		if args := splitArgs(value); len(args) > 0 {
			return args
		}
	}
	if runtime.GOOS == "windows" {
		return []string{"notepad"}
	}
	return []string{"vi"}
}

// splitArgs splits a command line on whitespace, keeping text in single or
// double quotes together: `"C:\Program Files\x.exe" -w` is two arguments.
// Backslashes are left alone, since they separate Windows paths.
func splitArgs(s string) []string {
	var args []string
	var arg strings.Builder
	inArg := false
	var quote rune
	for _, r := range s {
		switch {
		case quote != 0:
			if r == quote {
				quote = 0
			} else {
				arg.WriteRune(r)
			}
		case r == '"' || r == '\'':
			quote, inArg = r, true
		case r == ' ' || r == '\t':
			if inArg {
				args = append(args, arg.String())
				arg.Reset()
				inArg = false
			}
		default:
			arg.WriteRune(r)
			inArg = true
		}
	}
	if inArg {
		args = append(args, arg.String())
	}
	return args
}
