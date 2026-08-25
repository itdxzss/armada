package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestStoreConcurrentCancelAndFinishDoesNotBusy(t *testing.T) {
	stateDir := t.TempDir()
	finisher, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer finisher.Close()
	canceller, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer canceller.Close()

	for iteration := 0; iteration < 50; iteration++ {
		runID := fmt.Sprintf("cancel-finish-%02d", iteration)
		enqueueStoreRegressionRun(t, finisher, runID, 1)
		claimed, err := finisher.ClaimNext(time.Now())
		if err != nil || claimed == nil || claimed.Run.ID != runID {
			t.Fatalf("iteration %d claim = %#v, %v", iteration, claimed, err)
		}
		attempt, err := finisher.StartStage(runID, 0, "stages/01-check/attempt-1.log", time.Now())
		if err != nil {
			t.Fatalf("iteration %d start: %v", iteration, err)
		}
		zero := 0
		if err := finisher.FinishStage(
			runID, 0, attempt, StagePassed, &zero, "", strings.Repeat("a", 64), time.Now(),
		); err != nil {
			t.Fatalf("iteration %d finish stage: %v", iteration, err)
		}

		start := make(chan struct{})
		var wait sync.WaitGroup
		wait.Add(2)
		var (
			finishStatus RunStatus
			finishReason string
			finishErr    error
			cancelErr    error
		)
		go func() {
			defer wait.Done()
			<-start
			finishStatus, finishReason, finishErr = finisher.FinishRun(runID, RunPassed, "", time.Now())
		}()
		go func() {
			defer wait.Done()
			<-start
			_, cancelErr = canceller.RequestCancel(runID, time.Now())
		}()
		close(start)
		wait.Wait()
		if finishErr != nil || cancelErr != nil {
			t.Fatalf("iteration %d finish/cancel errors = %v / %v", iteration, finishErr, cancelErr)
		}

		detail, err := finisher.Get(runID)
		if err != nil {
			t.Fatalf("iteration %d get: %v", iteration, err)
		}
		switch detail.Run.Status {
		case RunPassed:
			if detail.Run.CancelRequested || finishStatus != RunPassed || finishReason != "" {
				t.Fatalf("iteration %d inconsistent PASS: %#v, finish=%s/%q", iteration, detail.Run, finishStatus, finishReason)
			}
		case RunCancelled:
			if !detail.Run.CancelRequested || finishStatus != RunCancelled || finishReason != "CANCEL_REQUESTED" {
				t.Fatalf("iteration %d inconsistent CANCELLED: %#v, finish=%s/%q", iteration, detail.Run, finishStatus, finishReason)
			}
		default:
			t.Fatalf("iteration %d terminal status = %s", iteration, detail.Run.Status)
		}
		if detail.Run.EvidenceReady {
			t.Fatalf("iteration %d evidence unexpectedly ready", iteration)
		}
		if err := finisher.MarkEvidenceReady(runID, detail.Run.Status); err != nil {
			t.Fatalf("iteration %d mark evidence: %v", iteration, err)
		}
	}
}

func TestStoreRecoversCommittedCancellation(t *testing.T) {
	stateDir := t.TempDir()
	beforeCrash, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	runID := "cancel-recovery"
	enqueueStoreRegressionRun(t, beforeCrash, runID, 2)
	if claimed, err := beforeCrash.ClaimNext(time.Now()); err != nil || claimed == nil {
		t.Fatalf("claim = %#v, %v", claimed, err)
	}
	if _, err := beforeCrash.StartStage(runID, 0, "stages/01-check-0/attempt-1.log", time.Now()); err != nil {
		t.Fatal(err)
	}
	if status, err := beforeCrash.RequestCancel(runID, time.Now()); err != nil || status != RunRunning {
		t.Fatalf("request cancel = %s, %v", status, err)
	}
	if err := beforeCrash.Close(); err != nil {
		t.Fatal(err)
	}

	afterCrash, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer afterCrash.Close()
	recovered, err := afterCrash.RecoverInterrupted(time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if len(recovered) != 1 || recovered[0] != runID {
		t.Fatalf("recovered = %#v", recovered)
	}
	detail, err := afterCrash.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.Status != RunCancelled || detail.Run.FailureReason != "CANCEL_REQUESTED" || !detail.Run.CancelRequested {
		t.Fatalf("recovered run = %#v", detail.Run)
	}
	if detail.Run.EvidenceReady {
		t.Fatal("recovered cancellation evidence unexpectedly ready")
	}
	if len(detail.Stages) != 2 || detail.Stages[0].Status != StageCancelled || detail.Stages[1].Status != StageCancelled {
		t.Fatalf("recovered stages = %#v", detail.Stages)
	}
	if len(detail.Attempts) != 1 || detail.Attempts[0].Status != StageCancelled {
		t.Fatalf("recovered attempts = %#v", detail.Attempts)
	}
	hash := strings.Repeat("b", 64)
	if err := afterCrash.SetRecoveredAttemptEvidence(runID, 0, 1, hash, ""); err != nil {
		t.Fatal(err)
	}
	if err := afterCrash.SetRecoveredAttemptEvidence(runID, 0, 1, hash, ""); err != nil {
		t.Fatalf("idempotent recovered SHA write: %v", err)
	}
	if err := afterCrash.SetRecoveredAttemptEvidence(runID, 0, 1, strings.Repeat("c", 64), ""); err == nil {
		t.Fatal("different recovered SHA unexpectedly replaced the stored hash")
	}
	detail, err = afterCrash.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Stages[0].SHA256 != hash || detail.Attempts[0].SHA256 != hash {
		t.Fatalf("recovered SHA not mirrored: %#v / %#v", detail.Stages[0], detail.Attempts[0])
	}
	pending, err := afterCrash.ListTerminalWithoutEvidence()
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 1 || pending[0].ID != runID {
		t.Fatalf("pending evidence = %#v", pending)
	}
	if err := afterCrash.MarkEvidenceReady(runID, RunCancelled); err != nil {
		t.Fatal(err)
	}
	pending, err = afterCrash.ListTerminalWithoutEvidence()
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("pending evidence after mark = %#v", pending)
	}
}

func TestStoreResumeRequiresReadyEvidence(t *testing.T) {
	stateDir := t.TempDir()
	store, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	runID := "resume-evidence-gate"
	enqueueStoreRegressionRun(t, store, runID, 1)
	if claimed, err := store.ClaimNext(time.Now()); err != nil || claimed == nil {
		t.Fatalf("claim = %#v, %v", claimed, err)
	}
	if _, err := store.StartStage(runID, 0, "stages/01-check-0/attempt-1.log", time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := store.RecoverInterrupted(time.Now()); err != nil {
		t.Fatal(err)
	}
	if err := store.Resume(runID); err == nil || !strings.Contains(err.Error(), "evidence is not ready") {
		t.Fatalf("resume before evidence error = %v", err)
	}
	const incomplete = "EVIDENCE_INCOMPLETE:LOG_MISSING"
	if err := store.SetRecoveredAttemptEvidence(runID, 0, 1, "", incomplete); err != nil {
		t.Fatal(err)
	}
	if err := store.SetRecoveredAttemptEvidence(runID, 0, 1, "", incomplete); err != nil {
		t.Fatalf("idempotent incomplete evidence write: %v", err)
	}
	detail, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(detail.Stages[0].Reason, "RUNNER_INTERRUPTED") ||
		!strings.Contains(detail.Stages[0].Reason, incomplete) ||
		!strings.Contains(detail.Attempts[0].Reason, incomplete) {
		t.Fatalf("incomplete evidence reasons = %#v / %#v", detail.Stages[0], detail.Attempts[0])
	}
	if err := store.MarkEvidenceReady(runID, RunPassed); err == nil {
		t.Fatal("mismatched terminal status unexpectedly marked evidence ready")
	}
	if err := store.MarkEvidenceReady(runID, RunFailed); err != nil {
		t.Fatal(err)
	}
	detail, err = store.Get(runID)
	if err != nil || !detail.Run.EvidenceReady {
		t.Fatalf("ready detail = %#v, %v", detail.Run, err)
	}
	if err := store.Resume(runID); err != nil {
		t.Fatal(err)
	}
	detail, err = store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.Status != RunQueued || detail.Run.EvidenceReady || detail.Stages[0].Status != StageQueued {
		t.Fatalf("resumed detail = %#v / %#v", detail.Run, detail.Stages)
	}
}

func enqueueStoreRegressionRun(t *testing.T, store *Store, runID string, stageCount int) {
	t.Helper()
	stages := make([]StageSpec, 0, stageCount)
	for index := 0; index < stageCount; index++ {
		stages = append(stages, StageSpec{
			ID:             fmt.Sprintf("check-%d", index),
			Command:        []string{"/usr/bin/env", "true"},
			TimeoutSeconds: 5,
		})
	}
	plan := Plan{
		SchemaVersion: planSchemaVersion,
		Profile:       "store-regression",
		Environment:   "local",
		Safety:        safetyReadOnly,
		Builds: BuildManifest{
			Backend:         strings.Repeat("a", 40),
			Frontend:        strings.Repeat("b", 40),
			WebProtocol:     strings.Repeat("c", 40),
			AndroidProtocol: strings.Repeat("d", 40),
		},
		Stages: stages,
	}
	planJSON, err := json.Marshal(plan)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Enqueue(runID, plan, planJSON, time.Now()); err != nil {
		t.Fatal(err)
	}
}
