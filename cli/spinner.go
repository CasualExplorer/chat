package main

// The spinner is adapted from Crush (github.com/charmbracelet/crush,
// internal/ui/anim), Copyright 2025-2026 Charmbracelet, Inc., used under
// FSL-1.1-MIT.

import (
	"fmt"
	"image/color"
	"math/rand/v2"
	"strings"
	"time"

	"charm.land/lipgloss/v2"
	"github.com/lucasb-eyer/go-colorful"
)

const (
	spinnerSize     = 15               // scrambled characters before the label
	spinnerInterval = time.Second / 20 // one animation frame
	spinnerBirth    = 20               // frames before every character has appeared
	spinnerFrames   = spinnerSize * 2  // the colour cycle loops after this many frames
	spinnerRunes    = "0123456789abcdefABCDEF~!@#$£€%^&*()+=_"
)

// spinner is Crush's "working" animation: a row of scrambled characters in a
// moving purple-pink gradient, which fade in from dots over the first second.
type spinner struct {
	frames [][]string // prerendered scrambled characters, per frame
	dots   [][]string // the same colours on the placeholder dot
	birth  []int      // frame at which each column switches from dot to character
	label  lipgloss.Style
	step   int
	age    int
}

func newSpinner(st *Styles) *spinner {
	runes := []rune(spinnerRunes)
	ramp := gradientRamp(spinnerSize*3, st.Primary, st.Secondary, st.Primary, st.Secondary)
	s := &spinner{
		label:  st.Spinner,
		frames: make([][]string, spinnerFrames),
		dots:   make([][]string, spinnerFrames),
		birth:  make([]int, spinnerSize),
	}
	for i := range spinnerFrames {
		s.frames[i] = make([]string, spinnerSize)
		s.dots[i] = make([]string, spinnerSize)
		for j := range spinnerSize {
			style := lipgloss.NewStyle().Foreground(ramp[i+j])
			s.frames[i][j] = style.Render(string(runes[rand.IntN(len(runes))]))
			s.dots[i][j] = style.Render(".")
		}
	}
	for j := range s.birth {
		s.birth[j] = rand.IntN(spinnerBirth)
	}
	return s
}

// advance moves the animation on by one frame.
func (s *spinner) advance() {
	s.step = (s.step + 1) % spinnerFrames
	s.age++
}

// view draws the current frame followed by the label, if any, and how long
// the request has been running.
func (s *spinner) view(label string, elapsed time.Duration) string {
	var b strings.Builder
	for j := range spinnerSize {
		if s.age < s.birth[j] {
			b.WriteString(s.dots[s.step][j])
		} else {
			b.WriteString(s.frames[s.step][j])
		}
	}
	if label != "" {
		b.WriteString(" " + s.label.Render(label))
	}
	b.WriteString(" " + s.label.Render(formatElapsed(elapsed)))
	return b.String()
}

func formatElapsed(d time.Duration) string {
	seconds, minutes, hours := int(d.Seconds()), int(d.Minutes()), int(d.Hours())
	switch {
	case hours >= 1:
		return fmt.Sprintf("%dh %dm", hours, minutes%60)
	case minutes >= 1:
		return fmt.Sprintf("%dm %ds", minutes, seconds%60)
	default:
		return fmt.Sprintf("%ds", seconds)
	}
}

// gradientRamp blends size colours through the given stops, in HCL so the
// blend stays in gamut.
func gradientRamp(size int, stops ...color.Color) []color.Color {
	points := make([]colorful.Color, len(stops))
	for i, c := range stops {
		points[i], _ = colorful.MakeColor(c)
	}
	segments := len(stops) - 1
	ramp := make([]color.Color, 0, size)
	for i := range segments {
		n := size / segments
		if i < size%segments {
			n++
		}
		for j := range n {
			ramp = append(ramp, points[i].BlendHcl(points[i+1], float64(j)/float64(n)))
		}
	}
	return ramp
}
