package main

// The logo's block letterforms, diagonal fields and gradient follow Crush's
// (github.com/charmbracelet/crush, internal/ui/logo), Copyright 2025-2026
// Charmbracelet, Inc., used under FSL-1.1-MIT. It spells this app's name:
// Crush's own wordmark is Charm's trademark.

import (
	"image/color"
	"math/rand/v2"
	"strings"

	"charm.land/lipgloss/v2"
	"github.com/charmbracelet/x/ansi"
)

const (
	logoName = "CHAT"
	diag     = "╱"
)

// letterform draws a three-row letter whose stretchable part is n cells wide.
type letterform struct {
	draw             func(n int) [3]string
	width            int // the stretchable part's normal width
	minWide, maxWide int // its width range when stretched
}

var logoLetters = []letterform{
	{ // C
		draw: func(n int) [3]string {
			return [3]string{"▄" + rep("▀", n), "█" + rep(" ", n), " " + rep("▀", n)}
		},
		width: 4, minWide: 7, maxWide: 12,
	},
	{ // H
		draw: func(n int) [3]string {
			return [3]string{"█" + rep(" ", n) + "█", "█" + rep("▀", n) + "█", "▀" + rep(" ", n) + "▀"}
		},
		width: 3, minWide: 8, maxWide: 12,
	},
	{ // A
		draw: func(n int) [3]string {
			return [3]string{"▄" + rep("▀", n) + "▄", "█" + rep("▀", n) + "█", "▀" + rep(" ", n) + "▀"}
		},
		width: 3, minWide: 8, maxWide: 12,
	},
	{ // T: n is the width of each arm
		draw: func(n int) [3]string {
			return [3]string{rep("▀", n) + "█" + rep("▀", n), rep(" ", n) + "█" + rep(" ", n), rep(" ", n) + "▀" + rep(" ", n)}
		},
		width: 2, minWide: 4, maxWide: 6,
	},
}

// The wide logo always stretches the same letter by the same amount, picked
// once at random, so it doesn't jitter as the window resizes.
var (
	logoStretchIndex = rand.IntN(len(logoLetters))
	logoStretchWidth = func() int {
		l := logoLetters[logoStretchIndex]
		return l.minWide + rand.IntN(l.maxWide-l.minWide)
	}()
)

func rep(s string, n int) string { return strings.Repeat(s, max(n, 0)) }

// logoTitle renders the block-letter name with a gradient across each row.
func logoTitle(s *Styles, stretch bool) []string {
	var rows [3]string
	for i, l := range logoLetters {
		n := l.width
		if stretch && i == logoStretchIndex {
			n = logoStretchWidth
		}
		for r, part := range l.draw(n) {
			if i > 0 {
				rows[r] += " "
			}
			rows[r] += part
		}
	}
	out := make([]string, len(rows))
	for i, row := range rows {
		out[i] = gradientText(row, s.Secondary, s.Primary, false)
	}
	return out
}

// wideLogo is the landing page header: the stretched title between diagonal
// fields, the right one stepping down row by row.
func wideLogo(s *Styles, width int) string {
	title := logoTitle(s, true)
	titleWidth := lipgloss.Width(title[0])
	const leftWidth = 6
	rightWidth := max(15, width-titleWidth-leftWidth-2)
	field := lipgloss.NewStyle().Foreground(s.Primary)
	lines := make([]string, len(title))
	for i, row := range title {
		line := field.Render(rep(diag, leftWidth)) + " " + row + " " + field.Render(rep(diag, rightWidth-i))
		lines[i] = ansi.Truncate(line, width, "")
	}
	return strings.Join(lines, "\n")
}

// sidebarLogo is the narrow logo at the top of the sidebar.
func sidebarLogo(s *Styles) string {
	title := logoTitle(s, false)
	field := lipgloss.NewStyle().Foreground(s.Primary).Render(rep(diag, lipgloss.Width(title[0])))
	return strings.Join(append(append([]string{field, field}, title...), field), "\n")
}

// smallLogo is the one-line logo for the compact header and short sidebars,
// followed by diagonals out to width.
func smallLogo(s *Styles, width int) string {
	name := gradientText(logoName, s.Secondary, s.Primary, true)
	if rest := width - lipgloss.Width(name) - 1; rest > 0 {
		name += " " + lipgloss.NewStyle().Foreground(s.Primary).Render(rep(diag, rest))
	}
	return name
}

// gradientText colours each character of s along a gradient from a to b.
func gradientText(s string, a, b color.Color, bold bool) string {
	runes := []rune(s)
	if len(runes) == 0 {
		return ""
	}
	ramp := gradientRamp(len(runes), a, b)
	var out strings.Builder
	for i, r := range runes {
		out.WriteString(lipgloss.NewStyle().Foreground(ramp[i]).Bold(bold).Render(string(r)))
	}
	return out.String()
}
