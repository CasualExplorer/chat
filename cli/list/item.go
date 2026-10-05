// Adapted from Crush (github.com/charmbracelet/crush, internal/ui/list),
// Copyright 2025-2026 Charmbracelet, Inc., used under FSL-1.1-MIT.

package list

// Item represents a single item in the lazy-loaded list.
//
// Items participate in the list-level render cache. The cache key
// for each item is (pointer, width, version), so items must bump their
// version (via the embedded *Versioned helper) on every mutation that
// changes the rendered output. A cached entry is emitted verbatim — no
// Render call — until either Version() bumps or the viewport width
// changes.
type Item interface {
	// Render returns the string representation of the item for the given
	// width.
	Render(width int) string

	// Version returns a monotonic counter that the list-level cache
	// uses to detect mutations. Items must increment the version
	// (via Versioned.Bump) on every state change that would alter
	// the rendered output.
	Version() uint64
}

// Versioned is a tiny embeddable helper that satisfies Item.Version()
// and provides a Bump() method to call from every state-mutating
// method. Items typically embed *Versioned alongside their other
// helpers; see messageItem in the main package for the usual wiring.
//
// Bump() is not safe for concurrent use; callers must hold whatever
// synchronization their item type already requires for state
// mutations. The list itself never reads Version() from a goroutine
// other than the UI thread.
type Versioned struct {
	v uint64
}

// NewVersioned returns a fresh *Versioned at version zero.
func NewVersioned() *Versioned {
	return &Versioned{}
}

// Version returns the current version counter.
func (vc *Versioned) Version() uint64 {
	return vc.v
}

// Bump advances the version counter by one. Mutators on items that
// affect the rendered output must call Bump exactly once per
// observable state change. Bumping more than once per change is
// harmless other than a single extra cache miss.
func (vc *Versioned) Bump() {
	vc.v++
}

// Focusable represents an item that can be aware of focus state changes.
type Focusable interface {
	// SetFocused sets the focus state of the item.
	SetFocused(focused bool)
}

// Highlightable represents an item that can highlight a portion of its content.
type Highlightable interface {
	// SetHighlight highlights the content from the given start to end
	// positions. Use -1 for no highlight.
	SetHighlight(startLine, startCol, endLine, endCol int)
	// Highlight returns the current highlight positions within the item.
	Highlight() (startLine, startCol, endLine, endCol int)
}
