package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRedactStructuredSecrets(t *testing.T) {
	tests := []struct {
		name   string
		input  string
		secret string
	}{
		{name: "json token", input: `{"token":"token-value-123"}`, secret: "token-value-123"},
		{name: "json password", input: `{"password": "password-value-123"}`, secret: "password-value-123"},
		{name: "json secret", input: `{"secret":"secret-value-123"}`, secret: "secret-value-123"},
		{name: "json api key", input: `{"apiKey" : "api-key-value-123"}`, secret: "api-key-value-123"},
		{name: "json cookie", input: `{"cookie":"session=cookie-value-123; theme=dark"}`, secret: "cookie-value-123"},
		{name: "json access token", input: `{"access_token":"access-token-value-123"}`, secret: "access-token-value-123"},
		{name: "json refresh token", input: `{"refreshToken":"refresh-token-value-123"}`, secret: "refresh-token-value-123"},
		{name: "json client secret", input: `{"client_secret":"client-secret-value-123"}`, secret: "client-secret-value-123"},
		{name: "json authorization", input: `{"authorization":"Basic authorization-value-123"}`, secret: "authorization-value-123"},
		{name: "json creds", input: `{"creds":"creds-value-123"}`, secret: "creds-value-123"},
		{name: "set cookie header", input: `Set-Cookie: session=set-cookie-value-123; Path=/; HttpOnly`, secret: "set-cookie-value-123"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := redact(test.input)
			if strings.Contains(got, test.secret) {
				t.Fatalf("redacted output still contains secret %q: %s", test.secret, got)
			}
			if !strings.Contains(got, "[REDACTED]") {
				t.Fatalf("redacted output has no marker: %s", got)
			}
		})
	}
}

func TestVerifiedReportRejectsTamperedEvidenceWithoutRewritingManifest(t *testing.T) {
	stateDir := t.TempDir()
	evidence := newEvidenceStore(stateDir)
	runID := "20260825T040404Z-abcdef12"
	if err := evidence.InitRun(runID, []byte("{}\n")); err != nil {
		t.Fatal(err)
	}
	log, relativeLog, err := evidence.OpenStageLog(runID, 0, "preflight", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := log.WriteLine("stdout", "original evidence"); err != nil {
		t.Fatal(err)
	}
	if err := log.Close(); err != nil {
		t.Fatal(err)
	}
	logHash, err := evidence.HashStageLog(runID, relativeLog)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	detail := evidenceTestDetail(runID, relativeLog, logHash, now)
	reportPath, err := evidence.Finalize(detail)
	if err != nil {
		t.Fatal(err)
	}
	runDir, err := evidence.runDir(runID)
	if err != nil {
		t.Fatal(err)
	}
	manifestPath := filepath.Join(runDir, "checksums.sha256")
	manifestBefore, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}

	if err := os.Remove(reportPath); err != nil {
		t.Fatal(err)
	}
	if _, err := evidence.VerifiedReport(detail); err != nil {
		t.Fatalf("rebuild verified report: %v", err)
	}
	manifestAfterVerifiedReport, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(manifestAfterVerifiedReport, manifestBefore) {
		t.Fatal("verified report rewrote an intact checksum manifest")
	}

	logPath := filepath.Join(runDir, filepath.FromSlash(relativeLog))
	file, err := os.OpenFile(logPath, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteString("tampered\n"); err != nil {
		file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	const reportSentinel = "report must remain untouched\n"
	if err := os.WriteFile(reportPath, []byte(reportSentinel), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := evidence.VerifiedReport(detail); err == nil || !strings.Contains(err.Error(), "checksum mismatch") {
		t.Fatalf("verified report after tamper error = %v", err)
	}
	manifestAfterTamper, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(manifestAfterTamper, manifestBefore) {
		t.Fatal("failed verified report rewrote checksum manifest")
	}
	reportAfterTamper, err := os.ReadFile(reportPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(reportAfterTamper) != reportSentinel {
		t.Fatalf("failed verified report modified report.md: %q", reportAfterTamper)
	}
}

func TestVerifiedReportRejectsSummaryFromAnotherTerminalState(t *testing.T) {
	stateDir := t.TempDir()
	evidence := newEvidenceStore(stateDir)
	runID := "20260825T041414Z-abcdef13"
	if err := evidence.InitRun(runID, []byte("{}\n")); err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	log, relativeLog, err := evidence.OpenStageLog(runID, 0, "preflight", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := log.WriteLine("stdout", "terminal evidence"); err != nil {
		t.Fatal(err)
	}
	if err := log.Close(); err != nil {
		t.Fatal(err)
	}
	logHash, err := evidence.HashStageLog(runID, relativeLog)
	if err != nil {
		t.Fatal(err)
	}
	detail := evidenceTestDetail(runID, relativeLog, logHash, now)
	if _, err := evidence.Finalize(detail); err != nil {
		t.Fatal(err)
	}
	detail.Run.Status = RunCancelled
	detail.Run.FailureReason = "CANCELLED_BEFORE_START"
	if _, err := evidence.VerifiedReport(detail); err == nil || !strings.Contains(err.Error(), "does not match") {
		t.Fatalf("stale summary verification error = %v", err)
	}
}

func TestFinalizeRejectsAttemptLogThatNoLongerMatchesDurableHash(t *testing.T) {
	stateDir := t.TempDir()
	evidence := newEvidenceStore(stateDir)
	runID := "20260825T042424Z-abcdef17"
	if err := evidence.InitRun(runID, []byte("{}\n")); err != nil {
		t.Fatal(err)
	}
	log, relativeLog, err := evidence.OpenStageLog(runID, 0, "preflight", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := log.WriteLine("stdout", "original terminal log"); err != nil {
		t.Fatal(err)
	}
	if err := log.Close(); err != nil {
		t.Fatal(err)
	}
	logHash, err := evidence.HashStageLog(runID, relativeLog)
	if err != nil {
		t.Fatal(err)
	}
	detail := evidenceTestDetail(runID, relativeLog, logHash, time.Now().UTC())
	runDir, err := evidence.runDir(runID)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Truncate(filepath.Join(runDir, filepath.FromSlash(relativeLog)), 0); err != nil {
		t.Fatal(err)
	}
	if _, err := evidence.Finalize(detail); err == nil || !strings.Contains(err.Error(), "does not match durable state") {
		t.Fatalf("finalize mismatched attempt error = %v", err)
	}
	if _, err := os.Stat(filepath.Join(runDir, "checksums.sha256")); !os.IsNotExist(err) {
		t.Fatalf("mismatched attempt unexpectedly produced a manifest: %v", err)
	}
}

func TestVerifiedReportRejectsIncompleteChecksumManifest(t *testing.T) {
	tests := []struct {
		name       string
		mutate     func(t *testing.T, runDir string, manifestPath string)
		wantReason string
	}{
		{
			name: "empty manifest",
			mutate: func(t *testing.T, _ string, manifestPath string) {
				t.Helper()
				if err := os.WriteFile(manifestPath, nil, 0o600); err != nil {
					t.Fatal(err)
				}
			},
			wantReason: "missing required file",
		},
		{
			name: "required plan and events omitted",
			mutate: func(t *testing.T, _ string, manifestPath string) {
				t.Helper()
				data, err := os.ReadFile(manifestPath)
				if err != nil {
					t.Fatal(err)
				}
				kept := make([]string, 0)
				for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
					if strings.HasSuffix(line, "  plan.json") || strings.HasSuffix(line, "  events.ndjson") {
						continue
					}
					kept = append(kept, line)
				}
				if err := os.WriteFile(manifestPath, []byte(strings.Join(kept, "\n")+"\n"), 0o600); err != nil {
					t.Fatal(err)
				}
			},
			wantReason: "missing required file",
		},
		{
			name: "new evidence file omitted",
			mutate: func(t *testing.T, runDir string, _ string) {
				t.Helper()
				if err := os.WriteFile(filepath.Join(runDir, "unexpected.json"), []byte("{}\n"), 0o600); err != nil {
					t.Fatal(err)
				}
			},
			wantReason: "does not cover evidence file",
		},
	}
	for index, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			stateDir := t.TempDir()
			evidence := newEvidenceStore(stateDir)
			runID := "20260825T04343" + string(rune('0'+index)) + "Z-abcdef19"
			if err := evidence.InitRun(runID, []byte("{}\n")); err != nil {
				t.Fatal(err)
			}
			log, relativeLog, err := evidence.OpenStageLog(runID, 0, "preflight", 1)
			if err != nil {
				t.Fatal(err)
			}
			if err := log.WriteLine("stdout", "complete manifest evidence"); err != nil {
				t.Fatal(err)
			}
			if err := log.Close(); err != nil {
				t.Fatal(err)
			}
			logHash, err := evidence.HashStageLog(runID, relativeLog)
			if err != nil {
				t.Fatal(err)
			}
			detail := evidenceTestDetail(runID, relativeLog, logHash, time.Now().UTC())
			reportPath, err := evidence.Finalize(detail)
			if err != nil {
				t.Fatal(err)
			}
			runDir, err := evidence.runDir(runID)
			if err != nil {
				t.Fatal(err)
			}
			manifestPath := filepath.Join(runDir, "checksums.sha256")
			test.mutate(t, runDir, manifestPath)
			const sentinel = "do not rewrite report on incomplete manifest\n"
			if err := os.WriteFile(reportPath, []byte(sentinel), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := evidence.VerifiedReport(detail); err == nil || !strings.Contains(err.Error(), test.wantReason) {
				t.Fatalf("incomplete manifest error = %v", err)
			}
			report, err := os.ReadFile(reportPath)
			if err != nil {
				t.Fatal(err)
			}
			if string(report) != sentinel {
				t.Fatalf("failed verification rewrote report: %q", report)
			}
		})
	}
}

func TestStageLogHelpersConstrainPaths(t *testing.T) {
	stateDir := t.TempDir()
	evidence := newEvidenceStore(stateDir)
	runID := "20260825T050505Z-abcdef34"
	if err := evidence.InitRun(runID, []byte("{}\n")); err != nil {
		t.Fatal(err)
	}
	log, relativeLog, err := evidence.OpenStageLog(runID, 0, "preflight", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := log.WriteLine("stdout", "hash me"); err != nil {
		t.Fatal(err)
	}
	if err := log.Close(); err != nil {
		t.Fatal(err)
	}
	gotHash, err := evidence.HashStageLog(runID, relativeLog)
	if err != nil {
		t.Fatal(err)
	}
	runDir, err := evidence.runDir(runID)
	if err != nil {
		t.Fatal(err)
	}
	wantHash, err := hashFile(filepath.Join(runDir, filepath.FromSlash(relativeLog)))
	if err != nil {
		t.Fatal(err)
	}
	if gotHash != wantHash {
		t.Fatalf("stage log hash = %s, want %s", gotHash, wantHash)
	}

	outside := filepath.Join(stateDir, "outside.log")
	if err := os.WriteFile(outside, []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, unsafePath := range []string{
		"../../outside.log",
		outside,
		"stages/01-preflight/../../../outside.log",
		"report.md",
	} {
		if _, err := evidence.HashStageLog(runID, unsafePath); err == nil {
			t.Errorf("HashStageLog accepted unsafe path %q", unsafePath)
		}
		if err := evidence.RemoveUntrackedStageLog(runID, unsafePath); err == nil {
			t.Errorf("RemoveUntrackedStageLog accepted unsafe path %q", unsafePath)
		}
	}
	if data, err := os.ReadFile(outside); err != nil || string(data) != "outside" {
		t.Fatalf("unsafe cleanup affected outside file: %q, %v", data, err)
	}

	symlinkRelative := "stages/01-preflight/attempt-2.log"
	symlinkPath := filepath.Join(runDir, filepath.FromSlash(symlinkRelative))
	if err := os.Symlink(outside, symlinkPath); err != nil {
		t.Fatal(err)
	}
	if _, err := evidence.HashStageLog(runID, symlinkRelative); err == nil {
		t.Fatal("HashStageLog accepted a symlink")
	}
	if err := evidence.RemoveUntrackedStageLog(runID, symlinkRelative); err == nil {
		t.Fatal("RemoveUntrackedStageLog accepted a symlink")
	}
	outsideDirectory := filepath.Join(stateDir, "outside-directory")
	if err := os.MkdirAll(outsideDirectory, 0o700); err != nil {
		t.Fatal(err)
	}
	parentSymlinkRelative := "stages/02-other/attempt-1.log"
	if err := os.WriteFile(filepath.Join(outsideDirectory, "attempt-1.log"), []byte("outside through parent"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outsideDirectory, filepath.Join(runDir, "stages", "02-other")); err != nil {
		t.Fatal(err)
	}
	if _, err := evidence.HashStageLog(runID, parentSymlinkRelative); err == nil {
		t.Fatal("HashStageLog followed a parent-directory symlink outside the run directory")
	}
	if err := evidence.RemoveUntrackedStageLog(runID, parentSymlinkRelative); err == nil {
		t.Fatal("RemoveUntrackedStageLog followed a parent-directory symlink outside the run directory")
	}

	if err := evidence.RemoveUntrackedStageLog(runID, relativeLog); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(runDir, filepath.FromSlash(relativeLog))); !os.IsNotExist(err) {
		t.Fatalf("stage log still exists after cleanup: %v", err)
	}
	if err := evidence.RemoveUntrackedStageLog(runID, relativeLog); err != nil {
		t.Fatalf("idempotent cleanup: %v", err)
	}
}

func evidenceTestDetail(runID string, logPath string, logHash string, now time.Time) RunDetail {
	exitCode := 0
	startedAt := now.Add(-time.Second)
	plan := Plan{
		SchemaVersion: planSchemaVersion,
		Profile:       "smoke",
		Environment:   "staging",
		Safety:        safetyReadOnly,
		Builds: BuildManifest{
			Backend:         strings.Repeat("1", 40),
			Frontend:        strings.Repeat("2", 40),
			WebProtocol:     strings.Repeat("3", 40),
			AndroidProtocol: strings.Repeat("4", 40),
		},
		Stages: []StageSpec{{ID: "preflight", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5}},
	}
	return RunDetail{
		Run: RunRecord{
			ID: runID, Plan: plan, Status: RunPassed, CreatedAt: startedAt,
			StartedAt: &startedAt, FinishedAt: &now,
		},
		Stages: []StageRecord{{
			Index: 0, ID: "preflight", Status: StagePassed, Attempts: 1,
			StartedAt: &startedAt, FinishedAt: &now, ExitCode: &exitCode,
			LogPath: logPath, SHA256: logHash,
		}},
		Attempts: []AttemptRecord{{
			StageIndex: 0, StageID: "preflight", Attempt: 1, Status: StagePassed,
			StartedAt: startedAt, FinishedAt: &now, ExitCode: &exitCode,
			LogPath: logPath, SHA256: logHash,
		}},
	}
}
