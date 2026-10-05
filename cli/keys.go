package main

// The key map follows Crush's (github.com/charmbracelet/crush,
// internal/ui/model/keys.go), Copyright 2025-2026 Charmbracelet, Inc., used
// under FSL-1.1-MIT.

import "charm.land/bubbles/v2/key"

type keyMap struct {
	// Global keys, whatever has focus.
	Quit      key.Binding
	Help      key.Binding
	Commands  key.Binding
	Models    key.Binding
	NewChat   key.Binding
	Tab       key.Binding
	Cancel    key.Binding // stops the reply that is streaming, when pressed twice
	EndFollow key.Binding // jumps to the newest message and follows it

	Editor struct {
		Send          key.Binding
		Newline       key.Binding
		OpenEditor    key.Binding
		HistoryPrev   key.Binding
		HistoryNext   key.Binding
		Scroll        key.Binding // pgup/pgdn scroll the chat from the editor
		History       key.Binding // HistoryPrev and HistoryNext, for the help
		SelectAll     key.Binding // handled by the textarea
		CopySelection key.Binding
		CutSelection  key.Binding
		PasteText     key.Binding // pastes the clipboard's text, for terminals that don't
	}

	Chat struct {
		Up             key.Binding // scroll a line
		Down           key.Binding
		UpDown         key.Binding // Up and Down, for the help
		UpOneItem      key.Binding // select the previous message
		DownOneItem    key.Binding
		UpDownOneItem  key.Binding // UpOneItem and DownOneItem, for the help
		PageUp         key.Binding
		PageDown       key.Binding
		HalfPageUp     key.Binding
		HalfPageDown   key.Binding
		Home           key.Binding
		End            key.Binding
		Expand         key.Binding
		Copy           key.Binding
		ClearHighlight key.Binding
	}
}

func defaultKeyMap() keyMap {
	km := keyMap{
		Quit:      key.NewBinding(key.WithKeys("ctrl+c"), key.WithHelp("ctrl+c", "quit")),
		Help:      key.NewBinding(key.WithKeys("ctrl+g"), key.WithHelp("ctrl+g", "more")),
		Commands:  key.NewBinding(key.WithKeys("ctrl+p"), key.WithHelp("ctrl+p", "commands")),
		Models:    key.NewBinding(key.WithKeys("ctrl+l"), key.WithHelp("ctrl+l", "models")),
		NewChat:   key.NewBinding(key.WithKeys("ctrl+n"), key.WithHelp("ctrl+n", "new chat")),
		Tab:       key.NewBinding(key.WithKeys("tab"), key.WithHelp("tab", "change focus")),
		Cancel:    key.NewBinding(key.WithKeys("esc"), key.WithHelp("esc", "cancel")),
		EndFollow: key.NewBinding(key.WithKeys("ctrl+end"), key.WithHelp("ctrl+end", "newest message")),
	}

	km.Editor.Send = key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "send"))
	km.Editor.Newline = key.NewBinding(key.WithKeys("shift+enter", "alt+enter", "ctrl+j"), key.WithHelp("shift+enter", "newline"))
	km.Editor.OpenEditor = key.NewBinding(key.WithKeys("ctrl+o"), key.WithHelp("ctrl+o", "open editor"))
	km.Editor.HistoryPrev = key.NewBinding(key.WithKeys("up"), key.WithHelp("↑", "previous message"))
	km.Editor.HistoryNext = key.NewBinding(key.WithKeys("down"), key.WithHelp("↓", "next message"))
	km.Editor.History = key.NewBinding(key.WithKeys("up", "down"), key.WithHelp("↑/↓", "history"))
	km.Editor.Scroll = key.NewBinding(key.WithKeys("pgup", "pgdown"), key.WithHelp("pgup/pgdn", "scroll"))
	km.Editor.SelectAll = key.NewBinding(key.WithKeys("ctrl+shift+a"), key.WithHelp("ctrl+shift+a", "select all"))
	km.Editor.CopySelection = key.NewBinding(key.WithKeys("ctrl+shift+c"), key.WithHelp("ctrl+shift+c", "copy selection"))
	km.Editor.CutSelection = key.NewBinding(key.WithKeys("ctrl+shift+x"), key.WithHelp("ctrl+shift+x", "cut selection"))
	km.Editor.PasteText = key.NewBinding(key.WithKeys("ctrl+shift+v"), key.WithHelp("ctrl+shift+v", "paste text"))

	km.Chat.Up = key.NewBinding(key.WithKeys("up", "ctrl+k", "k"), key.WithHelp("↑", "up"))
	km.Chat.Down = key.NewBinding(key.WithKeys("down", "ctrl+j", "j"), key.WithHelp("↓", "down"))
	km.Chat.UpDown = key.NewBinding(key.WithKeys("up", "down"), key.WithHelp("↑↓", "scroll"))
	km.Chat.UpOneItem = key.NewBinding(key.WithKeys("shift+up", "K"), key.WithHelp("shift+↑", "previous message"))
	km.Chat.DownOneItem = key.NewBinding(key.WithKeys("shift+down", "J"), key.WithHelp("shift+↓", "next message"))
	km.Chat.UpDownOneItem = key.NewBinding(key.WithKeys("shift+up", "shift+down"), key.WithHelp("shift+↑↓", "select message"))
	km.Chat.PageUp = key.NewBinding(key.WithKeys("pgup", "b"), key.WithHelp("b/pgup", "page up"))
	km.Chat.PageDown = key.NewBinding(key.WithKeys("pgdown", "f"), key.WithHelp("f/pgdn", "page down"))
	km.Chat.HalfPageUp = key.NewBinding(key.WithKeys("u"), key.WithHelp("u", "half page up"))
	km.Chat.HalfPageDown = key.NewBinding(key.WithKeys("d"), key.WithHelp("d", "half page down"))
	km.Chat.Home = key.NewBinding(key.WithKeys("g", "home"), key.WithHelp("g", "top"))
	km.Chat.End = key.NewBinding(key.WithKeys("G", "end"), key.WithHelp("G", "bottom"))
	km.Chat.Expand = key.NewBinding(key.WithKeys("space", " ", "enter"), key.WithHelp("space", "expand/collapse"))
	km.Chat.Copy = key.NewBinding(key.WithKeys("c", "y", "C", "Y"), key.WithHelp("c/y", "copy"))
	km.Chat.ClearHighlight = key.NewBinding(key.WithKeys("esc"), key.WithHelp("esc", "clear selection"))
	return km
}
