// Package xchroma is a Chroma formatter that styles code with Lip Gloss.
//
// It is adapted from Crush (github.com/charmbracelet/crush,
// internal/ui/xchroma), Copyright 2025-2026 Charmbracelet, Inc., used under
// FSL-1.1-MIT.
package xchroma

import (
	"fmt"
	"image/color"
	"io"
	"strings"

	"charm.land/lipgloss/v2"
	"github.com/alecthomas/chroma/v2"
)

// Formatter returns a Chroma formatter that colours tokens with Lip Gloss,
// so they get the theme's exact colours rather than the nearest of 256, on
// the given background (nil for none).
func Formatter(bgColor color.Color) chroma.Formatter {
	return chroma.FormatterFunc(func(w io.Writer, style *chroma.Style, it chroma.Iterator) error {
		for token := it(); token != chroma.EOF; token = it() {
			entry := style.Get(token.Type)
			if entry.IsZero() {
				if _, err := fmt.Fprint(w, token.Value); err != nil {
					return err
				}
				continue
			}

			s := lipgloss.NewStyle().Background(bgColor)
			if entry.Bold == chroma.Yes {
				s = s.Bold(true)
			}
			if entry.Underline == chroma.Yes {
				s = s.Underline(true)
			}
			if entry.Italic == chroma.Yes {
				s = s.Italic(true)
			}
			if entry.Colour.IsSet() {
				s = s.Foreground(lipgloss.Color(entry.Colour.String()))
			}

			if _, err := fmt.Fprint(w, renderLines(s, token.Value)); err != nil {
				return err
			}
		}
		return nil
	})
}

// renderLines styles each line of value separately, joining them with plain
// newlines. Tokens may contain newlines (Chroma's CommentSingle includes the
// line terminator), and Lip Gloss pads a multi-line string to equal line
// widths, which would indent the line after a comment.
func renderLines(s lipgloss.Style, value string) string {
	if !strings.Contains(value, "\n") {
		return s.Render(value)
	}
	lines := strings.Split(value, "\n")
	for i, line := range lines {
		lines[i] = s.Render(line)
	}
	return strings.Join(lines, "\n")
}
