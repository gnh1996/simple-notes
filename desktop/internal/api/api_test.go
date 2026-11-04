package api

import "testing"

func TestLogPathStripsQuery(t *testing.T) {
	cases := map[string]string{
		"/desktop/notes/sync?limit=500":    "/desktop/notes/sync",
		"/desktop/notes?keyword=%E5%AF%86": "/desktop/notes",
		"/desktop/notes/sync":              "/desktop/notes/sync",
		"/desktop/notes?keyword=a?b":       "/desktop/notes",
		"":                                 "",
	}
	for input, want := range cases {
		if got := logPath(input); got != want {
			t.Fatalf("logPath(%q) = %q, want %q", input, got, want)
		}
	}
}
