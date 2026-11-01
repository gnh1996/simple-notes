package store

import "testing"

func TestUIStateLifecycle(t *testing.T) {
	db := openTestStore(t)

	value, found, err := db.GetUIState("open_tabs")
	if err != nil {
		t.Fatal(err)
	}
	if found || value != "" {
		t.Fatalf("empty state = %q, found=%v", value, found)
	}

	if err := db.SetUIState("open_tabs", `{"tabs":[],"active":0}`); err != nil {
		t.Fatal(err)
	}
	value, found, err = db.GetUIState("open_tabs")
	if err != nil {
		t.Fatal(err)
	}
	if !found || value != `{"tabs":[],"active":0}` {
		t.Fatalf("state = %q, found=%v", value, found)
	}

	if err := db.SetUIState("open_tabs", `{"tabs":[],"active":0}`+" "); err != nil {
		t.Fatal(err)
	}
	value, _, _ = db.GetUIState("open_tabs")
	if value != `{"tabs":[],"active":0} ` {
		t.Fatalf("upsert = %q", value)
	}

	if err := db.DeleteUIState("open_tabs"); err != nil {
		t.Fatal(err)
	}
	if _, found, _ := db.GetUIState("open_tabs"); found {
		t.Fatal("ui_state 未删除")
	}
}
