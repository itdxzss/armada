package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestStartStageFailureRemovesUntrackedAttemptLog(t *testing.T) {
	stateDir := t.TempDir()
	plan := validPlan([]StageSpec{{
		ID: "preflight", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5,
	}})
	store, evidence, runID := enqueueTestRun(t, stateDir, plan, "20260825T061616Z-abcdef14")
	defer store.Close()
	detail, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	runner := newDaemon(store, evidence, stateDir)
	if err := runner.executeRun(context.Background(), detail); err == nil {
		t.Fatal("executeRun unexpectedly started a stage for an unclaimed run")
	}
	logPath := filepath.Join(
		stateDir, "runs", runID, "stages", "01-preflight", "attempt-1.log",
	)
	if _, err := os.Stat(logPath); !os.IsNotExist(err) {
		t.Fatalf("untracked attempt log remains after StartStage failure: %v", err)
	}
	detail, err = store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.Status != RunQueued || detail.Stages[0].Status != StageQueued || detail.Stages[0].Attempts != 0 {
		t.Fatalf("failed start changed durable state: %#v / %#v", detail.Run, detail.Stages[0])
	}
}

func TestDaemonFinalizesTerminalRunLeftBetweenStateAndEvidence(t *testing.T) {
	stateDir := t.TempDir()
	plan := validPlan([]StageSpec{{
		ID: "preflight", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5,
	}})
	store, evidence, runID := enqueueTestRun(t, stateDir, plan, "20260825T062626Z-abcdef15")
	defer store.Close()
	if claimed, err := store.ClaimNext(time.Now()); err != nil || claimed == nil {
		t.Fatalf("claim = %#v, %v", claimed, err)
	}
	log, relativeLog, err := evidence.OpenStageLog(runID, 0, "preflight", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := log.WriteLine("stdout", "completed before finalization crash"); err != nil {
		t.Fatal(err)
	}
	if err := log.Close(); err != nil {
		t.Fatal(err)
	}
	attempt, err := store.StartStage(runID, 0, relativeLog, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	hash, err := evidence.HashStageLog(runID, relativeLog)
	if err != nil {
		t.Fatal(err)
	}
	zero := 0
	if err := store.FinishStage(runID, 0, attempt, StagePassed, &zero, "", hash, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.FinishRun(runID, RunPassed, "", time.Now()); err != nil {
		t.Fatal(err)
	}
	before, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if before.Run.Status != RunPassed || before.Run.EvidenceReady {
		t.Fatalf("pre-recovery run = %#v", before.Run)
	}
	runner := newDaemon(store, evidence, stateDir)
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	after, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if after.Run.Status != RunPassed || !after.Run.EvidenceReady {
		t.Fatalf("recovered finalization = %#v", after.Run)
	}
	runDir, err := evidence.runDir(runID)
	if err != nil {
		t.Fatal(err)
	}
	if err := verifyChecksums(runDir); err != nil {
		t.Fatal(err)
	}
	report, err := os.ReadFile(filepath.Join(runDir, "report.md"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(report), "Outcome: `PASS`") {
		t.Fatalf("report = %s", report)
	}
}

func TestSecondRecoveryCompletesInterruptedAttemptEvidence(t *testing.T) {
	stateDir := t.TempDir()
	plan := validPlan([]StageSpec{{
		ID: "preflight", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5,
	}})
	store, evidence, runID := enqueueTestRun(t, stateDir, plan, "20260825T063636Z-abcdef16")
	defer store.Close()
	if claimed, err := store.ClaimNext(time.Now()); err != nil || claimed == nil {
		t.Fatalf("claim = %#v, %v", claimed, err)
	}
	log, relativeLog, err := evidence.OpenStageLog(runID, 0, "preflight", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := log.WriteLine("stdout", "written before the first recovery crashed"); err != nil {
		t.Fatal(err)
	}
	if err := log.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := store.StartStage(runID, 0, relativeLog, time.Now()); err != nil {
		t.Fatal(err)
	}
	if recovered, err := store.RecoverInterrupted(time.Now()); err != nil || len(recovered) != 1 {
		t.Fatalf("first recovery = %#v, %v", recovered, err)
	}
	between, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if between.Run.Status != RunFailed || between.Attempts[0].SHA256 != "" || between.Run.EvidenceReady {
		t.Fatalf("between recoveries = %#v / %#v", between.Run, between.Attempts)
	}
	runner := newDaemon(store, evidence, stateDir)
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	after, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if !after.Run.EvidenceReady || after.Attempts[0].SHA256 == "" {
		t.Fatalf("second recovery did not seal attempt evidence: %#v / %#v", after.Run, after.Attempts)
	}
	actual, err := evidence.HashStageLog(runID, relativeLog)
	if err != nil {
		t.Fatal(err)
	}
	if after.Attempts[0].SHA256 != actual || after.Stages[0].SHA256 != actual {
		t.Fatalf("recovered hashes = %s / %s, want %s", after.Attempts[0].SHA256, after.Stages[0].SHA256, actual)
	}
}

func TestResumedStageWithoutNewAttemptCanReachASealedTerminal(t *testing.T) {
	tests := []struct {
		name       string
		transition func(t *testing.T, store *Store, runID string)
		wantStatus RunStatus
	}{
		{
			name: "daemon crashes after claim but before retry starts",
			transition: func(t *testing.T, store *Store, runID string) {
				t.Helper()
				if claimed, err := store.ClaimNext(time.Now()); err != nil || claimed == nil {
					t.Fatalf("claim resumed run = %#v, %v", claimed, err)
				}
				if recovered, err := store.RecoverInterrupted(time.Now()); err != nil || len(recovered) != 1 {
					t.Fatalf("recover before retry start = %#v, %v", recovered, err)
				}
			},
			wantStatus: RunFailed,
		},
		{
			name: "queued retry is cancelled before it starts",
			transition: func(t *testing.T, store *Store, runID string) {
				t.Helper()
				if status, err := store.RequestCancel(runID, time.Now()); err != nil || status != RunCancelled {
					t.Fatalf("cancel resumed run = %s, %v", status, err)
				}
			},
			wantStatus: RunCancelled,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			stateDir := t.TempDir()
			plan := validPlan([]StageSpec{{
				ID: "retry", Command: []string{"/usr/bin/env", "false"}, TimeoutSeconds: 5,
			}})
			store, evidence, runID := enqueueTestRun(t, stateDir, plan, "20260825T064646Z-abcdef18")
			defer store.Close()
			runner := newDaemon(store, evidence, stateDir)
			if err := runner.Serve(context.Background(), true); err != nil {
				t.Fatal(err)
			}
			failed, err := store.Get(runID)
			if err != nil {
				t.Fatal(err)
			}
			if failed.Run.Status != RunFailed || !failed.Run.EvidenceReady || failed.Attempts[0].SHA256 == "" {
				t.Fatalf("initial failure = %#v / %#v", failed.Run, failed.Attempts)
			}
			if err := store.Resume(runID); err != nil {
				t.Fatal(err)
			}
			resumed, err := store.Get(runID)
			if err != nil {
				t.Fatal(err)
			}
			if resumed.Stages[0].Status != StageQueued || resumed.Stages[0].LogPath != "" || resumed.Stages[0].Attempts != 1 {
				t.Fatalf("resumed stage = %#v", resumed.Stages[0])
			}
			test.transition(t, store, runID)
			if err := runner.Serve(context.Background(), true); err != nil {
				t.Fatal(err)
			}
			terminal, err := store.Get(runID)
			if err != nil {
				t.Fatal(err)
			}
			if terminal.Run.Status != test.wantStatus || !terminal.Run.EvidenceReady {
				t.Fatalf("sealed terminal = %#v / %#v", terminal.Run, terminal.Stages)
			}
			if terminal.Attempts[0].SHA256 != failed.Attempts[0].SHA256 {
				t.Fatalf("historical attempt hash changed: %s / %s", terminal.Attempts[0].SHA256, failed.Attempts[0].SHA256)
			}
			if test.wantStatus == RunFailed {
				if err := store.Resume(runID); err != nil {
					t.Fatalf("sealed recovered run cannot resume: %v", err)
				}
			}
		})
	}
}
