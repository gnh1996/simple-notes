package auth

import "testing"

func TestNormalizeBaseURL(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"localhost:8080", "http://localhost:8080/api"},
		{"http://localhost:8080", "http://localhost:8080/api"},
		{"http://localhost:8080/", "http://localhost:8080/api"},
		{"http://localhost:8080/api", "http://localhost:8080/api"},
		{"https://notes.example.com/api/", "https://notes.example.com/api"},
		{"https://example.com/notes", "https://example.com/notes/api"},
	}
	for _, tc := range cases {
		got, err := NormalizeBaseURL(tc.in)
		if err != nil {
			t.Fatalf("%q: %v", tc.in, err)
		}
		if got != tc.want {
			t.Fatalf("%q => %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestNormalizeBaseURLRejects(t *testing.T) {
	for _, in := range []string{
		"",
		"ftp://example.com",
		"http://",
		"http://user:pass@example.com",
		"http://example.com?x=1",
		"http://example.com#frag",
	} {
		if _, err := NormalizeBaseURL(in); err == nil {
			t.Fatalf("%q 应被拒绝", in)
		}
	}
}

func TestScopeIDStable(t *testing.T) {
	a := ScopeID("http://localhost:8080/api", "u1")
	b := ScopeID("http://localhost:8080/api", "u1")
	c := ScopeID("http://localhost:8080/api", "u2")
	if a != b {
		t.Fatalf("同一服务器 + 用户应得到相同 scope: %q vs %q", a, b)
	}
	if a == c {
		t.Fatal("不同用户不应共享 scope")
	}
}
