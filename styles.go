package main

// The palette, styles and markdown themes below are adapted from Crush
// (github.com/charmbracelet/crush, internal/ui/styles), Copyright 2025-2026
// Charmbracelet, Inc., used under FSL-1.1-MIT.

import (
	"fmt"
	"image/color"

	"charm.land/bubbles/v2/help"
	"charm.land/bubbles/v2/textarea"
	"charm.land/bubbles/v2/textinput"
	tea "charm.land/bubbletea/v2"
	"charm.land/glamour/v2"
	"charm.land/glamour/v2/ansi"
	"charm.land/lipgloss/v2"
	"github.com/alecthomas/chroma/v2/formatters"
	"github.com/charmbracelet/x/exp/charmtone"

	"chat/xchroma"
)

const (
	modelIcon        = "◇"
	sectionSeparator = "─"
	scrollbarThumb   = "┃"
	scrollbarTrack   = "│"

	// chromaFormatter is the name code blocks are highlighted with. Glamour
	// only takes a formatter by name, so it is registered with Chroma.
	chromaFormatter = "chat"
)

func init() {
	formatters.Register(chromaFormatter, xchroma.Formatter(nil))
}

// palette holds a theme's colours. Every style is derived from one, so a
// theme is just another palette.
type palette struct {
	Primary, Secondary, Accent, Keyword color.Color

	FgBase, FgMoreSubtle, FgSubtle, FgMostSubtle, OnPrimary color.Color

	BgBase, BgLeastVisible, BgLessVisible, BgMostVisible, Separator color.Color

	Destructive, Error, WarningSubtle, Warning, Attention color.Color
	Info, InfoMoreSubtle                                  color.Color
	Success, SuccessMoreSubtle, SuccessMostSubtle         color.Color
	Button, ButtonSubtle                                  color.Color
}

// pantera is Crush's default dark theme (Charmtone Pantera). The app paints
// its own background, so it works whatever the terminal's colours are.
func pantera() palette {
	return palette{
		Primary:   charmtone.Charple,
		Secondary: charmtone.Dolly,
		Accent:    charmtone.Bok,
		Keyword:   charmtone.Blush,

		FgBase:       charmtone.Sash,
		FgMoreSubtle: charmtone.Squid,
		FgSubtle:     charmtone.Smoke,
		FgMostSubtle: charmtone.Oyster,
		OnPrimary:    charmtone.Butter,

		BgBase:         charmtone.Pepper,
		BgLeastVisible: charmtone.BBQ,
		BgLessVisible:  charmtone.Char,
		BgMostVisible:  charmtone.Iron,
		Separator:      charmtone.Char,

		Destructive:       charmtone.Coral,
		Error:             charmtone.Sriracha,
		WarningSubtle:     charmtone.Zest,
		Warning:           charmtone.Mustard,
		Attention:         charmtone.Tang,
		Info:              charmtone.Malibu,
		InfoMoreSubtle:    charmtone.Sardine,
		Success:           charmtone.Julep,
		SuccessMoreSubtle: charmtone.Bok,
		SuccessMostSubtle: charmtone.Guac,
		Button:            charmtone.Dolly,
		ButtonSubtle:      charmtone.Char,
	}
}

// Styles is every style the UI draws with, built from one palette.
type Styles struct {
	palette

	Base, Muted, Subtle lipgloss.Style
	SectionLine         lipgloss.Style
	Spinner             lipgloss.Style // the spinner's label and timer
	TextSelection       lipgloss.Style // text highlighted with the mouse
	ScrollbarThumb      lipgloss.Style
	ScrollbarTrack      lipgloss.Style

	Messages struct {
		// Each message line is prefixed with one of these (they render no
		// content); the focused ones mark the message selected in the chat.
		UserBlurred, UserFocused           lipgloss.Style
		AssistantBlurred, AssistantFocused lipgloss.Style

		ThinkingBox            lipgloss.Style
		ThinkingHint           lipgloss.Style // "… (n lines hidden)"
		ThinkingFooterTitle    lipgloss.Style
		ThinkingFooterDuration lipgloss.Style
		Canceled               lipgloss.Style
		ErrorTag, ErrorTitle   lipgloss.Style // a failed reply's banner
	}

	// The input's prompt column (see editorPrompt).
	Editor struct {
		PromptIcon, PromptDots, PromptDotsBlurred lipgloss.Style
	}

	// Provider list dots.
	ProviderOffline, ProviderOnline lipgloss.Style

	// Messages drawn over the help line.
	Status struct {
		ErrorIndicator, ErrorMessage lipgloss.Style
		WarnIndicator, WarnMessage   lipgloss.Style
		InfoIndicator, InfoMessage   lipgloss.Style
	}

	Dialog struct {
		View, Title, InputPrompt, List, HelpView lipgloss.Style
		NormalItem, SelectedItem                 lipgloss.Style
		InfoBlurred, InfoFocused                 lipgloss.Style
		QuitFrame, QuitContent, QuitHint         lipgloss.Style
		ButtonFocused, ButtonBlurred             lipgloss.Style
	}

	Help      help.Styles
	Textarea  textarea.Styles
	TextInput textinput.Styles

	Markdown      ansi.StyleConfig // replies and user messages
	QuietMarkdown ansi.StyleConfig // thinking summaries
}

func newStyles(p palette) *Styles {
	s := &Styles{palette: p}
	s.Base = lipgloss.NewStyle().Foreground(p.FgBase)
	s.Muted = lipgloss.NewStyle().Foreground(p.FgMoreSubtle)
	s.Subtle = lipgloss.NewStyle().Foreground(p.FgMostSubtle)
	s.SectionLine = lipgloss.NewStyle().Foreground(p.Separator)
	s.Spinner = s.Subtle
	s.TextSelection = lipgloss.NewStyle().Foreground(p.BgBase).Background(p.Secondary)
	s.ScrollbarThumb = lipgloss.NewStyle().Foreground(p.Secondary)
	s.ScrollbarTrack = lipgloss.NewStyle().Foreground(p.Separator)

	focusedBorder := lipgloss.Border{Left: "▌"}
	s.Messages.UserBlurred = lipgloss.NewStyle().PaddingLeft(1).BorderLeft(true).
		BorderStyle(lipgloss.NormalBorder()).BorderForeground(p.Primary)
	s.Messages.UserFocused = s.Messages.UserBlurred.BorderStyle(focusedBorder)
	s.Messages.AssistantBlurred = lipgloss.NewStyle().PaddingLeft(2)
	s.Messages.AssistantFocused = lipgloss.NewStyle().PaddingLeft(1).BorderLeft(true).
		BorderStyle(focusedBorder).BorderForeground(p.SuccessMostSubtle)
	s.Messages.ThinkingBox = s.Subtle.Background(p.BgLeastVisible)
	s.Messages.ThinkingHint = s.Muted
	s.Messages.ThinkingFooterTitle = s.Muted
	s.Messages.ThinkingFooterDuration = s.Subtle
	s.Messages.Canceled = lipgloss.NewStyle().Foreground(p.FgSubtle).Italic(true)
	s.Messages.ErrorTag = lipgloss.NewStyle().Padding(0, 1).Background(p.Destructive).Foreground(p.OnPrimary).SetString("ERROR")
	s.Messages.ErrorTitle = lipgloss.NewStyle().Foreground(p.FgSubtle)

	s.Editor.PromptIcon = lipgloss.NewStyle().Foreground(p.Success).Bold(true).SetString("  > ")
	s.Editor.PromptDots = lipgloss.NewStyle().Foreground(p.SuccessMostSubtle).SetString("::: ")
	s.Editor.PromptDotsBlurred = s.Editor.PromptDots.Foreground(p.FgMoreSubtle)

	s.ProviderOffline = lipgloss.NewStyle().Foreground(p.BgMostVisible).SetString("●")
	s.ProviderOnline = s.ProviderOffline.Foreground(p.SuccessMostSubtle)

	indicator := lipgloss.NewStyle().Padding(0, 1).Bold(true)
	message := lipgloss.NewStyle().Padding(0, 1)
	s.Status.ErrorIndicator = indicator.Foreground(p.BgBase).Background(p.Destructive).SetString("ERROR")
	s.Status.ErrorMessage = message.Foreground(p.OnPrimary).Background(p.Error)
	s.Status.WarnIndicator = indicator.Foreground(p.BgMostVisible).Background(p.Warning).SetString("WARNING")
	s.Status.WarnMessage = message.Foreground(p.BgMostVisible).Background(p.WarningSubtle)
	s.Status.InfoIndicator = indicator.Foreground(p.BgLessVisible).Background(p.Success).SetString("OKAY!")
	s.Status.InfoMessage = message.Foreground(p.BgLessVisible).Background(p.SuccessMostSubtle)

	s.Help = help.Styles{
		ShortKey:       s.Muted,
		ShortDesc:      s.Subtle,
		ShortSeparator: s.SectionLine,
		Ellipsis:       s.SectionLine,
		FullKey:        s.Muted,
		FullDesc:       s.Subtle,
		FullSeparator:  s.SectionLine,
	}

	s.Dialog.View = lipgloss.NewStyle().Border(lipgloss.RoundedBorder()).BorderForeground(p.Primary)
	s.Dialog.Title = lipgloss.NewStyle().Padding(0, 1).Foreground(p.Primary)
	s.Dialog.InputPrompt = lipgloss.NewStyle().Margin(1, 1)
	s.Dialog.List = lipgloss.NewStyle().Margin(0, 0, 1, 0)
	s.Dialog.HelpView = lipgloss.NewStyle().Padding(0, 1).AlignHorizontal(lipgloss.Left)
	s.Dialog.NormalItem = lipgloss.NewStyle().Padding(0, 1).Foreground(p.FgBase)
	s.Dialog.SelectedItem = lipgloss.NewStyle().Padding(0, 1).Background(p.Primary).Foreground(p.OnPrimary)
	s.Dialog.InfoBlurred = lipgloss.NewStyle().Foreground(p.FgMostSubtle)
	s.Dialog.InfoFocused = lipgloss.NewStyle().Foreground(p.FgBase)
	s.Dialog.QuitFrame = lipgloss.NewStyle().BorderForeground(p.Primary).Border(lipgloss.RoundedBorder()).Padding(1, 2)
	s.Dialog.QuitContent = lipgloss.NewStyle().Foreground(p.FgBase)
	s.Dialog.QuitHint = lipgloss.NewStyle().Foreground(p.FgMostSubtle)
	s.Dialog.ButtonFocused = lipgloss.NewStyle().Foreground(p.OnPrimary).Background(p.Button)
	s.Dialog.ButtonBlurred = lipgloss.NewStyle().Foreground(p.FgBase).Background(p.ButtonSubtle)

	s.Textarea = textarea.Styles{
		Focused: textarea.StyleState{
			Base:             s.Base,
			Text:             s.Base,
			LineNumber:       s.Subtle,
			CursorLine:       s.Base,
			CursorLineNumber: s.Subtle,
			Placeholder:      s.Subtle,
			Prompt:           s.Base.Foreground(p.Accent),
			Selection:        s.Base.Foreground(p.OnPrimary).Background(p.Secondary),
		},
		Blurred: textarea.StyleState{
			Base:             s.Base,
			Text:             s.Muted,
			LineNumber:       s.Muted,
			CursorLine:       s.Base,
			CursorLineNumber: s.Muted,
			Placeholder:      s.Subtle,
			Prompt:           s.Muted,
			Selection:        s.Base.Foreground(p.OnPrimary).Background(p.Secondary),
		},
		Cursor: textarea.CursorStyle{
			Color: p.Secondary,
			Shape: tea.CursorBlock,
			Blink: true,
		},
	}
	s.TextInput = textinput.Styles{
		Focused: textinput.StyleState{
			Text:        s.Base,
			Placeholder: s.Subtle,
			Prompt:      s.Base.Foreground(p.Accent),
		},
		Blurred: textinput.StyleState{
			Text:        s.Muted,
			Placeholder: s.Subtle,
			Prompt:      s.Muted,
		},
		Cursor: textinput.CursorStyle{
			Color: p.Secondary,
			Shape: tea.CursorBlock,
			Blink: true,
		},
	}

	s.Markdown = markdownStyle(p)
	s.QuietMarkdown = quietMarkdownStyle(p)
	return s
}

// common is what the chat items and dialogs share: the styles, and markdown
// renderers for the width messages are wrapped to.
type common struct {
	styles    *Styles
	width     int // what renderers wrap to
	renderers map[rendererKind]*glamour.TermRenderer
}

type rendererKind int

const (
	rendererReply rendererKind = iota // assistant replies
	rendererUser                      // user messages: typed newlines kept
	rendererQuiet                     // thinking summaries
)

func newCommon(s *Styles) *common {
	return &common{styles: s, renderers: make(map[rendererKind]*glamour.TermRenderer)}
}

// renderer returns a markdown renderer of the given kind that wraps to
// width, or nil if glamour can't build one. Every message is rendered at
// the same width, so only that width's renderers are kept: resizing the
// window would otherwise leave a set behind for every width it passed.
func (c *common) renderer(kind rendererKind, width int) *glamour.TermRenderer {
	if width != c.width {
		clear(c.renderers)
		c.width = width
	}
	if r, ok := c.renderers[kind]; ok {
		return r
	}
	style := c.styles.Markdown
	if kind == rendererQuiet {
		style = c.styles.QuietMarkdown
	}
	opts := []glamour.TermRendererOption{
		glamour.WithStyles(style),
		glamour.WithWordWrap(width),
		glamour.WithChromaFormatter(chromaFormatter),
	}
	if kind == rendererUser {
		opts = append(opts, glamour.WithPreservedNewLines())
	}
	r, err := glamour.NewTermRenderer(opts...)
	if err != nil {
		r = nil
	}
	c.renderers[kind] = r
	return r
}

func hex(c color.Color) *string {
	r, g, b, _ := c.RGBA()
	s := fmt.Sprintf("#%02x%02x%02x", r>>8, g>>8, b>>8)
	return &s
}

// markdownStyle is the theme for replies and user messages.
func markdownStyle(p palette) ansi.StyleConfig {
	return ansi.StyleConfig{
		Document: ansi.StyleBlock{
			StylePrimitive: ansi.StylePrimitive{Color: hex(p.FgSubtle)},
		},
		BlockQuote: ansi.StyleBlock{
			Indent:      new(uint(1)),
			IndentToken: new("│ "),
		},
		List: ansi.StyleList{LevelIndent: 2},
		Heading: ansi.StyleBlock{
			StylePrimitive: ansi.StylePrimitive{
				BlockSuffix: "\n",
				Color:       hex(p.Info),
				Bold:        new(true),
			},
		},
		H1: ansi.StyleBlock{
			StylePrimitive: ansi.StylePrimitive{
				Prefix:          " ",
				Suffix:          " ",
				Color:           hex(p.WarningSubtle),
				BackgroundColor: hex(p.Primary),
				Bold:            new(true),
			},
		},
		H2: ansi.StyleBlock{StylePrimitive: ansi.StylePrimitive{Prefix: "## "}},
		H3: ansi.StyleBlock{StylePrimitive: ansi.StylePrimitive{Prefix: "### "}},
		H4: ansi.StyleBlock{StylePrimitive: ansi.StylePrimitive{Prefix: "#### "}},
		H5: ansi.StyleBlock{StylePrimitive: ansi.StylePrimitive{Prefix: "##### "}},
		H6: ansi.StyleBlock{
			StylePrimitive: ansi.StylePrimitive{
				Prefix: "###### ",
				Color:  hex(p.SuccessMostSubtle),
				Bold:   new(false),
			},
		},
		Strikethrough: ansi.StylePrimitive{CrossedOut: new(true)},
		Emph:          ansi.StylePrimitive{Italic: new(true)},
		Strong:        ansi.StylePrimitive{Bold: new(true)},
		HorizontalRule: ansi.StylePrimitive{
			Color:  hex(p.Separator),
			Format: "\n--------\n",
		},
		Item:        ansi.StylePrimitive{BlockPrefix: "• "},
		Enumeration: ansi.StylePrimitive{BlockPrefix: ". "},
		Task: ansi.StyleTask{
			Ticked:   "[✓] ",
			Unticked: "[ ] ",
		},
		Link: ansi.StylePrimitive{
			Color:     hex(charmtone.Zinc),
			Underline: new(true),
		},
		LinkText: ansi.StylePrimitive{
			Color: hex(p.SuccessMostSubtle),
			Bold:  new(true),
		},
		Image: ansi.StylePrimitive{
			Color:     hex(charmtone.Cheeky),
			Underline: new(true),
		},
		ImageText: ansi.StylePrimitive{
			Color:  hex(p.FgMoreSubtle),
			Format: "Image: {{.text}} →",
		},
		Code: ansi.StyleBlock{
			StylePrimitive: ansi.StylePrimitive{
				Prefix:          " ",
				Suffix:          " ",
				Color:           hex(p.Destructive),
				BackgroundColor: hex(p.BgLessVisible),
			},
		},
		CodeBlock: ansi.StyleCodeBlock{
			StyleBlock: ansi.StyleBlock{
				StylePrimitive: ansi.StylePrimitive{Color: hex(p.BgLessVisible)},
				Margin:         new(uint(2)),
			},
			Chroma: &ansi.Chroma{
				Text:                ansi.StylePrimitive{Color: hex(p.FgSubtle)},
				Error:               ansi.StylePrimitive{Color: hex(p.OnPrimary), BackgroundColor: hex(p.Error)},
				Comment:             ansi.StylePrimitive{Color: hex(p.FgMostSubtle)},
				CommentPreproc:      ansi.StylePrimitive{Color: hex(charmtone.Bengal)},
				Keyword:             ansi.StylePrimitive{Color: hex(p.Info)},
				KeywordReserved:     ansi.StylePrimitive{Color: hex(charmtone.Pony)},
				KeywordNamespace:    ansi.StylePrimitive{Color: hex(charmtone.Pony)},
				KeywordType:         ansi.StylePrimitive{Color: hex(charmtone.Guppy)},
				Operator:            ansi.StylePrimitive{Color: hex(charmtone.Salmon)},
				Punctuation:         ansi.StylePrimitive{Color: hex(p.WarningSubtle)},
				Name:                ansi.StylePrimitive{Color: hex(p.FgSubtle)},
				NameBuiltin:         ansi.StylePrimitive{Color: hex(p.Accent)},
				NameTag:             ansi.StylePrimitive{Color: hex(charmtone.Mauve)},
				NameAttribute:       ansi.StylePrimitive{Color: hex(charmtone.Hazy)},
				NameClass:           ansi.StylePrimitive{Color: hex(charmtone.Salt), Underline: new(true), Bold: new(true)},
				NameDecorator:       ansi.StylePrimitive{Color: hex(p.Attention)},
				NameFunction:        ansi.StylePrimitive{Color: hex(p.SuccessMostSubtle)},
				LiteralNumber:       ansi.StylePrimitive{Color: hex(p.Success)},
				LiteralString:       ansi.StylePrimitive{Color: hex(charmtone.Cumin)},
				LiteralStringEscape: ansi.StylePrimitive{Color: hex(p.SuccessMoreSubtle)},
				GenericDeleted:      ansi.StylePrimitive{Color: hex(p.Destructive)},
				GenericEmph:         ansi.StylePrimitive{Italic: new(true)},
				GenericInserted:     ansi.StylePrimitive{Color: hex(p.SuccessMostSubtle)},
				GenericStrong:       ansi.StylePrimitive{Bold: new(true)},
				GenericSubheading:   ansi.StylePrimitive{Color: hex(p.FgMoreSubtle)},
				Background:          ansi.StylePrimitive{BackgroundColor: hex(p.BgLessVisible)},
			},
		},
		DefinitionDescription: ansi.StylePrimitive{BlockPrefix: "\n "},
	}
}

// quietMarkdownStyle is the muted theme for the thinking box: structure
// without colour, on the box's background.
func quietMarkdownStyle(p palette) ansi.StyleConfig {
	fg, bg := hex(p.FgMoreSubtle), hex(p.BgLeastVisible)
	plain := ansi.StylePrimitive{Color: fg, BackgroundColor: bg}
	heading := func(prefix string) ansi.StyleBlock {
		p := plain
		p.Prefix = prefix
		return ansi.StyleBlock{StylePrimitive: p}
	}
	return ansi.StyleConfig{
		Document: ansi.StyleBlock{StylePrimitive: plain},
		BlockQuote: ansi.StyleBlock{
			StylePrimitive: plain,
			Indent:         new(uint(1)),
			IndentToken:    new("│ "),
		},
		List: ansi.StyleList{LevelIndent: 2},
		Heading: ansi.StyleBlock{StylePrimitive: ansi.StylePrimitive{
			BlockSuffix: "\n", Bold: new(true), Color: fg, BackgroundColor: bg,
		}},
		H1:             heading(" "),
		H2:             heading("## "),
		H3:             heading("### "),
		H4:             heading("#### "),
		H5:             heading("##### "),
		H6:             heading("###### "),
		Strikethrough:  ansi.StylePrimitive{CrossedOut: new(true), Color: fg, BackgroundColor: bg},
		Emph:           ansi.StylePrimitive{Italic: new(true), Color: fg, BackgroundColor: bg},
		Strong:         ansi.StylePrimitive{Bold: new(true), Color: fg, BackgroundColor: bg},
		HorizontalRule: ansi.StylePrimitive{Format: "\n--------\n", Color: fg, BackgroundColor: bg},
		Item:           ansi.StylePrimitive{BlockPrefix: "• ", Color: fg, BackgroundColor: bg},
		Enumeration:    ansi.StylePrimitive{BlockPrefix: ". ", Color: fg, BackgroundColor: bg},
		Task:           ansi.StyleTask{StylePrimitive: plain, Ticked: "[✓] ", Unticked: "[ ] "},
		Link:           ansi.StylePrimitive{Underline: new(true), Color: fg, BackgroundColor: bg},
		LinkText:       ansi.StylePrimitive{Bold: new(true), Color: fg, BackgroundColor: bg},
		Code:           ansi.StyleBlock{StylePrimitive: plain},
		CodeBlock: ansi.StyleCodeBlock{StyleBlock: ansi.StyleBlock{
			StylePrimitive: plain,
			Margin:         new(uint(2)),
		}},
	}
}
