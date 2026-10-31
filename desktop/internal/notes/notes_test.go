package notes

import (
	"testing"

	"cc.naihao/simplenotes/desktop/internal/store"
)

func TestContentHashNormalizesLineEndings(t *testing.T) {
	base := store.NoteRow{Title: "t", Content: "a\nb", Tags: []string{"x", "y"}}
	crlf := base
	crlf.Content = "a\r\nb"

	if ContentHash(base) != ContentHash(crlf) {
		t.Fatal("CRLF 与 LF 应得到相同 hash")
	}
}

func TestContentHashCoversSyncFields(t *testing.T) {
	base := store.NoteRow{Title: "t", Content: "a", Tags: []string{"x"}}
	changedTitle := base
	changedTitle.Title = "t2"
	changedFolder := base
	changedFolder.FolderID = "f1"
	changedPinned := base
	changedPinned.Pinned = true

	seen := map[string]bool{ContentHash(base): true}
	for _, row := range []store.NoteRow{changedTitle, changedFolder, changedPinned} {
		hash := ContentHash(row)
		if seen[hash] {
			t.Fatalf("同步字段变化应改变 hash: %+v", row)
		}
		seen[hash] = true
	}
}

func TestSortedTagNames(t *testing.T) {
	got := sortedTagNames([]tagBrief{{Name: "b"}, {Name: " a "}, {Name: ""}, {Name: "b"}})
	want := []string{"a", "b"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
}
