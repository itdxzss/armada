package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestRunStatusReportAndRedaction(t *testing.T) {
	stateDir := t.TempDir()
	secret := "runner-secret-value-7f31"
	t.Setenv("STAGING_ACCEPT_TEST_SECRET", secret)
	plan := validPlan([]StageSpec{
		{
			ID: "preflight",
			Command: []string{
				"/bin/sh", "-c", `printf 'Authorization: Bearer %s\nnormal output\n' "$STAGING_ACCEPT_TEST_SECRET"`,
			},
			TimeoutSeconds: 5,
		},
		{ID: "second-check", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5},
	})
	planPath := writeTestPlan(t, plan)
	var queued bytes.Buffer
	if code := runMain(
		[]string{"run", "--state-dir", stateDir, "--plan", planPath}, &queued, &bytes.Buffer{},
	); code != 0 {
		t.Fatalf("run command exit = %d", code)
	}
	runID := strings.TrimSpace(queued.String())

	store, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	evidence := newEvidenceStore(stateDir)
	runner := newDaemon(store, evidence, stateDir)
	runner.heartbeatInterval = 20 * time.Millisecond
	if err := runner.Serve(context.Background(), true); err != nil {
		store.Close()
		t.Fatal(err)
	}
	detail, err := store.Get(runID)
	if err != nil {
		store.Close()
		t.Fatal(err)
	}
	if detail.Run.Status != RunPassed {
		t.Fatalf("run status = %s, want PASS", detail.Run.Status)
	}
	if len(detail.Stages) != 2 || detail.Stages[0].Attempts != 1 || detail.Stages[1].Attempts != 1 {
		t.Fatalf("stage attempts = %#v", detail.Stages)
	}
	if len(detail.Attempts) != 2 {
		t.Fatalf("attempt records = %#v", detail.Attempts)
	}

	var statusJSON bytes.Buffer
	if code := runMain(
		[]string{"status", "--state-dir", stateDir, "--json", runID}, &statusJSON, &bytes.Buffer{},
	); code != 0 {
		t.Fatalf("status command exit = %d", code)
	}
	var statusDetail RunDetail
	if err := json.Unmarshal(statusJSON.Bytes(), &statusDetail); err != nil {
		t.Fatal(err)
	}
	if statusDetail.Run.ID != runID || statusDetail.Run.Status != RunPassed {
		t.Fatalf("status detail = %#v", statusDetail.Run)
	}
	var report bytes.Buffer
	if code := runMain(
		[]string{"report", "--state-dir", stateDir, runID}, &report, &bytes.Buffer{},
	); code != 0 {
		t.Fatalf("report command exit = %d", code)
	}
	if !strings.Contains(report.String(), "Outcome: `PASS`") {
		t.Fatalf("report = %s", report.String())
	}
	runDir, err := evidence.runDir(runID)
	if err != nil {
		t.Fatal(err)
	}
	if err := verifyChecksums(runDir); err != nil {
		t.Fatalf("verify checksums: %v", err)
	}
	manifestPath := filepath.Join(runDir, "checksums.sha256")
	manifestBeforeTamper, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	assertMode(t, stateDir, 0o700)
	assertMode(t, filepath.Join(stateDir, "runner.db"), 0o600)
	assertMode(t, filepath.Join(runDir, "plan.json"), 0o600)
	assertMode(t, filepath.Join(runDir, filepath.FromSlash(detail.Attempts[0].LogPath)), 0o600)
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	assertTreeExcludes(t, stateDir, secret)
	assertTreeContains(t, runDir, "[REDACTED]")

	logPath := filepath.Join(runDir, filepath.FromSlash(detail.Attempts[0].LogPath))
	file, err := os.OpenFile(logPath, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteString("tampered\n"); err != nil {
		file.Close()
		t.Fatal(err)
	}
	file.Close()
	if err := verifyChecksums(runDir); err == nil || !strings.Contains(err.Error(), "checksum mismatch") {
		t.Fatalf("tamper verification error = %v", err)
	}
	var tamperedReportErr bytes.Buffer
	if code := runMain(
		[]string{"report", "--state-dir", stateDir, runID}, &bytes.Buffer{}, &tamperedReportErr,
	); code == 0 || !strings.Contains(tamperedReportErr.String(), "checksum mismatch") {
		t.Fatalf("tampered report command = %d / %s", code, tamperedReportErr.String())
	}
	manifestAfterTamper, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(manifestBeforeTamper, manifestAfterTamper) {
		t.Fatal("report command rewrote the manifest after evidence tampering")
	}
}

func TestFailureResumeSkipsPassedStage(t *testing.T) {
	stateDir := t.TempDir()
	firstCounter := filepath.Join(stateDir, "first.count")
	secondCounter := filepath.Join(stateDir, "second.count")
	gate := filepath.Join(stateDir, "second.ready")
	plan := validPlan([]StageSpec{
		{
			ID:             "first",
			Command:        []string{"/bin/sh", "-c", fmt.Sprintf("printf x >> %q", firstCounter)},
			TimeoutSeconds: 5,
		},
		{
			ID: "second",
			Command: []string{"/bin/sh", "-c", fmt.Sprintf(
				"if [ -f %q ]; then printf x >> %q; else touch %q; exit 7; fi", gate, secondCounter, gate,
			)},
			TimeoutSeconds: 5,
		},
	})
	store, evidence, runID := enqueueTestRun(t, stateDir, plan, "20260825T010101Z-00000001")
	defer store.Close()
	runner := newDaemon(store, evidence, stateDir)
	runner.heartbeatInterval = 20 * time.Millisecond
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	failed, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if failed.Run.Status != RunFailed || failed.Stages[1].ExitCode == nil || *failed.Stages[1].ExitCode != 7 {
		t.Fatalf("first outcome = %#v / %#v", failed.Run, failed.Stages)
	}
	if err := store.Resume(runID); err != nil {
		t.Fatal(err)
	}
	if err := evidence.AppendEvent(eventRecord{RunID: runID, Type: "run_resumed", Status: string(RunQueued)}); err != nil {
		t.Fatal(err)
	}
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	passed, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if passed.Run.Status != RunPassed || passed.Stages[0].Attempts != 1 || passed.Stages[1].Attempts != 2 {
		t.Fatalf("resumed outcome = %#v / %#v", passed.Run, passed.Stages)
	}
	if got := mustRead(t, firstCounter); got != "x" {
		t.Fatalf("first stage ran more than once: %q", got)
	}
	if got := mustRead(t, secondCounter); got != "x" {
		t.Fatalf("second success count = %q", got)
	}
	if len(passed.Attempts) != 3 || passed.Attempts[1].Status != StageFailed || passed.Attempts[2].Status != StagePassed {
		t.Fatalf("attempt history = %#v", passed.Attempts)
	}
	for _, attempt := range passed.Attempts {
		if _, err := os.Stat(filepath.Join(stateDir, "runs", runID, filepath.FromSlash(attempt.LogPath))); err != nil {
			t.Fatalf("attempt log %s: %v", attempt.LogPath, err)
		}
	}
}

func TestStageProcessContextIsAbsoluteInheritedAndStableAcrossRetry(t *testing.T) {
	stateDir := t.TempDir()
	workingDirectory, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	relativeStateDir, err := filepath.Rel(workingDirectory, stateDir)
	if err != nil {
		t.Fatal(err)
	}
	root, runtimeStore, _, err := openRuntime(relativeStateDir)
	if err != nil {
		t.Fatal(err)
	}
	if err := runtimeStore.Close(); err != nil {
		t.Fatal(err)
	}

	contextOutput := filepath.Join(t.TempDir(), "stage-context.txt")
	retryGate := filepath.Join(t.TempDir(), "retry.ready")
	secret := "stage-context-secret-7e21"
	t.Setenv("STAGING_ACCEPT_EXISTING_ENV", "inherited-value")
	t.Setenv("STAGING_ACCEPT_EXPECTED_ENV", "inherited-value")
	t.Setenv("STAGING_ACCEPT_TEST_SECRET", secret)
	t.Setenv("STAGING_ACCEPT_EXPECTED_SECRET", secret)
	t.Setenv("STAGING_ACCEPT_CONTEXT_OUTPUT", contextOutput)
	t.Setenv("STAGING_ACCEPT_CONTEXT_GATE", retryGate)
	t.Setenv("STAGING_ACCEPT_RUN_ID", "forged-run")
	t.Setenv("STAGING_ACCEPT_STAGE_ID", "forged-stage")
	t.Setenv("STAGING_ACCEPT_RUN_DIR", "/forged/run")
	plan := validPlan([]StageSpec{{
		ID: "retry-context",
		Command: []string{
			"/bin/sh", "-c", `
test "$STAGING_ACCEPT_EXISTING_ENV" = "$STAGING_ACCEPT_EXPECTED_ENV" || exit 20
test "$STAGING_ACCEPT_TEST_SECRET" = "$STAGING_ACCEPT_EXPECTED_SECRET" || exit 21
case "$STAGING_ACCEPT_RUN_DIR" in /*) ;; *) exit 22 ;; esac
printf '%s\t%s\t%s\n' "$STAGING_ACCEPT_RUN_ID" "$STAGING_ACCEPT_STAGE_ID" "$STAGING_ACCEPT_RUN_DIR" >> "$STAGING_ACCEPT_CONTEXT_OUTPUT"
if [ ! -e "$STAGING_ACCEPT_CONTEXT_GATE" ]; then
  touch "$STAGING_ACCEPT_CONTEXT_GATE"
  exit 7
fi
`,
		},
		TimeoutSeconds: 5,
	}})
	const runID = "20260825T070707Z-abcdef19"
	store, evidence, _ := enqueueTestRun(t, root, plan, runID)
	defer store.Close()
	runner := newDaemon(store, evidence, root)
	runner.heartbeatInterval = 20 * time.Millisecond
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	failed, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if failed.Run.Status != RunFailed || failed.Stages[0].Attempts != 1 {
		t.Fatalf("first attempt = %#v / %#v", failed.Run, failed.Stages[0])
	}
	if err := store.Resume(runID); err != nil {
		t.Fatal(err)
	}
	if err := evidence.AppendEvent(eventRecord{RunID: runID, Type: "run_resumed", Status: string(RunQueued)}); err != nil {
		t.Fatal(err)
	}
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	passed, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if passed.Run.Status != RunPassed || passed.Stages[0].Attempts != 2 {
		t.Fatalf("resumed attempt = %#v / %#v", passed.Run, passed.Stages[0])
	}

	wantRunDir := filepath.Join(root, "runs", runID)
	if !filepath.IsAbs(wantRunDir) {
		t.Fatalf("expected run directory is not absolute: %s", wantRunDir)
	}
	wantLine := strings.Join([]string{runID, "retry-context", wantRunDir}, "\t")
	lines := strings.Split(strings.TrimSpace(mustRead(t, contextOutput)), "\n")
	if len(lines) != 2 || lines[0] != wantLine || lines[1] != wantLine {
		t.Fatalf("attempt contexts = %#v, want two copies of %q", lines, wantLine)
	}
	assertTreeExcludes(t, root, secret)
}

func TestBuildStageEnvironmentRejectsUnsafeRunDirectory(t *testing.T) {
	for _, runDirectory := range []string{"relative/run", "/safe/../escape"} {
		t.Run(runDirectory, func(t *testing.T) {
			_, err := buildStageEnvironment(
				[]string{"PATH=/usr/bin"},
				"20260825T070707Z-abcdef19",
				"preflight",
				runDirectory,
			)
			if err == nil || !strings.Contains(err.Error(), "absolute clean path") {
				t.Fatalf("buildStageEnvironment(%q) error = %v", runDirectory, err)
			}
		})
	}
}

func TestRunningCancelKillsProcessGroupAndReports(t *testing.T) {
	stateDir := t.TempDir()
	pidFile := filepath.Join(stateDir, "child.pid")
	nextMarker := filepath.Join(stateDir, "should-not-run")
	plan := validPlan([]StageSpec{
		{
			ID:             "blocking",
			Command:        []string{"/bin/sh", "-c", fmt.Sprintf("echo $$ > %q; trap '' TERM; sleep 30", pidFile)},
			TimeoutSeconds: 60,
		},
		{
			ID:             "next",
			Command:        []string{"/usr/bin/env", "touch", nextMarker},
			TimeoutSeconds: 5,
		},
	})
	store, evidence, runID := enqueueTestRun(t, stateDir, plan, "20260825T010101Z-00000002")
	defer store.Close()
	runner := newDaemon(store, evidence, stateDir)
	runner.heartbeatInterval = 20 * time.Millisecond
	runner.terminateGrace = 50 * time.Millisecond
	done := make(chan error, 1)
	go func() { done <- runner.Serve(context.Background(), true) }()
	waitFor(t, 3*time.Second, func() bool {
		detail, err := store.Get(runID)
		return err == nil && detail.Stages[0].Status == StageRunning
	})
	if _, err := store.RequestCancel(runID, time.Now()); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("cancel did not stop the runner")
	}
	detail, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.Status != RunCancelled || detail.Stages[0].Status != StageCancelled || detail.Stages[1].Status != StageCancelled {
		t.Fatalf("cancel outcome = %#v / %#v", detail.Run, detail.Stages)
	}
	if _, err := os.Stat(nextMarker); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("next stage unexpectedly ran: %v", err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(mustRead(t, pidFile)))
	if err != nil {
		t.Fatal(err)
	}
	if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
		t.Fatalf("process %d still exists: %v", pid, err)
	}
	if _, err := os.Stat(filepath.Join(stateDir, "runs", runID, "report.md")); err != nil {
		t.Fatal(err)
	}
}

func TestStageTimeoutKillsProcessGroupAndReports(t *testing.T) {
	stateDir := t.TempDir()
	pidFile := filepath.Join(stateDir, "timeout.pid")
	plan := validPlan([]StageSpec{{
		ID:             "timeout",
		Command:        []string{"/bin/sh", "-c", fmt.Sprintf("echo $$ > %q; trap '' TERM; sleep 30", pidFile)},
		TimeoutSeconds: 1,
	}})
	store, evidence, runID := enqueueTestRun(t, stateDir, plan, "20260825T010101Z-00000003")
	defer store.Close()
	runner := newDaemon(store, evidence, stateDir)
	runner.heartbeatInterval = 20 * time.Millisecond
	runner.terminateGrace = 50 * time.Millisecond
	started := time.Now()
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("timeout took %s", elapsed)
	}
	detail, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.Status != RunFailed || detail.Stages[0].Status != StageTimedOut || detail.Run.FailureReason != "STAGE_TIMEOUT" {
		t.Fatalf("timeout outcome = %#v / %#v", detail.Run, detail.Stages)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(mustRead(t, pidFile)))
	if err != nil {
		t.Fatal(err)
	}
	if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
		t.Fatalf("process %d still exists: %v", pid, err)
	}
	if _, err := os.Stat(filepath.Join(stateDir, "runs", runID, "report.md")); err != nil {
		t.Fatal(err)
	}
}

func TestDaemonCrashIsReportedAndExplicitResumeContinues(t *testing.T) {
	if os.Getenv("STAGING_ACCEPT_HELPER_STATE") != "" {
		t.Skip("parent-only test")
	}
	stateDir := t.TempDir()
	firstCounter := filepath.Join(stateDir, "crash-first.count")
	secondCounter := filepath.Join(stateDir, "crash-second.count")
	startedMarker := filepath.Join(stateDir, "crash-second.started")
	plan := validPlan([]StageSpec{
		{
			ID:             "checkpoint",
			Command:        []string{"/bin/sh", "-c", fmt.Sprintf("printf x >> %q", firstCounter)},
			TimeoutSeconds: 5,
		},
		{
			ID: "interrupted",
			Command: []string{"/bin/sh", "-c", fmt.Sprintf(
				"touch %q; sleep 1; printf x >> %q", startedMarker, secondCounter,
			)},
			TimeoutSeconds: 5,
		},
	})
	store, _, runID := enqueueTestRun(t, stateDir, plan, "20260825T010101Z-00000004")
	defer store.Close()

	first := helperDaemonCommand(t, stateDir)
	if err := first.Start(); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 3*time.Second, func() bool {
		detail, err := store.Get(runID)
		_, markerErr := os.Stat(startedMarker)
		return err == nil && detail.Stages[0].Status == StagePassed &&
			detail.Stages[1].Status == StageRunning && markerErr == nil
	})
	if err := first.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = first.Wait()
	time.Sleep(1200 * time.Millisecond)

	recoverProcess := helperDaemonCommand(t, stateDir)
	if output, err := recoverProcess.CombinedOutput(); err != nil {
		t.Fatalf("recovery helper: %v: %s", err, output)
	}
	interrupted, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if interrupted.Run.Status != RunFailed || interrupted.Run.FailureReason != "RUNNER_INTERRUPTED" ||
		interrupted.Stages[1].Status != StageInterrupted {
		t.Fatalf("recovered outcome = %#v / %#v", interrupted.Run, interrupted.Stages)
	}
	if _, err := os.Stat(filepath.Join(stateDir, "runs", runID, "report.md")); err != nil {
		t.Fatal(err)
	}
	if err := store.Resume(runID); err != nil {
		t.Fatal(err)
	}
	resumeProcess := helperDaemonCommand(t, stateDir)
	if output, err := resumeProcess.CombinedOutput(); err != nil {
		t.Fatalf("resume helper: %v: %s", err, output)
	}
	final, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if final.Run.Status != RunPassed || final.Stages[0].Attempts != 1 || final.Stages[1].Attempts != 2 {
		t.Fatalf("final outcome = %#v / %#v", final.Run, final.Stages)
	}
	if got := mustRead(t, firstCounter); got != "x" {
		t.Fatalf("checkpoint stage repeated: %q", got)
	}
	if len(final.Attempts) != 3 || final.Attempts[1].Status != StageInterrupted || final.Attempts[2].Status != StagePassed {
		t.Fatalf("attempt history = %#v", final.Attempts)
	}
	if final.Attempts[1].SHA256 == "" {
		t.Fatal("interrupted attempt log was not hashed during recovery")
	}
}

func TestHelperDaemonProcess(t *testing.T) {
	stateDir := os.Getenv("STAGING_ACCEPT_HELPER_STATE")
	if stateDir == "" {
		return
	}
	store, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	runner := newDaemon(store, newEvidenceStore(stateDir), stateDir)
	runner.heartbeatInterval = 20 * time.Millisecond
	runner.terminateGrace = 50 * time.Millisecond
	if err := runner.Serve(context.Background(), true); err != nil {
		t.Fatal(err)
	}
}

func TestPlanValidationAndDaemonLock(t *testing.T) {
	plan := validPlan([]StageSpec{{ID: "one", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5}})
	plan.Stages = append(plan.Stages, plan.Stages[0])
	if err := plan.Validate(); err == nil || !strings.Contains(err.Error(), "duplicate") {
		t.Fatalf("duplicate validation error = %v", err)
	}
	plan = validPlan([]StageSpec{{
		ID: "secret", Command: []string{"/usr/bin/env", "--token=raw"}, TimeoutSeconds: 5,
	}})
	if err := plan.Validate(); err == nil || !strings.Contains(err.Error(), "secret-like") {
		t.Fatalf("secret validation error = %v", err)
	}
	valid := validPlan([]StageSpec{{ID: "valid", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5}})
	validJSON, err := json.Marshal(valid)
	if err != nil {
		t.Fatal(err)
	}
	trailingPath := filepath.Join(t.TempDir(), "trailing.json")
	if err := os.WriteFile(trailingPath, append(validJSON, []byte("\n{}\n")...), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := loadPlan(trailingPath); err == nil || !strings.Contains(err.Error(), "exactly one") {
		t.Fatalf("trailing JSON error = %v", err)
	}

	canary := validPlan([]StageSpec{{ID: "execute", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5}})
	canary.Safety = safetyControlledCanary
	canary.SafetyEnvelopeRef = "group-classification-v1"
	if err := canary.Validate(); err != nil {
		t.Fatalf("controlled canary plan validation = %v", err)
	}
	missingEnvelope := canary
	missingEnvelope.SafetyEnvelopeRef = ""
	if err := missingEnvelope.Validate(); err == nil || !strings.Contains(err.Error(), "safetyEnvelopeRef") {
		t.Fatalf("missing canary envelope validation error = %v", err)
	}
	readOnlyEnvelope := validPlan([]StageSpec{{ID: "execute", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5}})
	readOnlyEnvelope.SafetyEnvelopeRef = "unexpected-envelope"
	if err := readOnlyEnvelope.Validate(); err == nil || !strings.Contains(err.Error(), "read-only") {
		t.Fatalf("read-only envelope validation error = %v", err)
	}
	unknownSafety := canary
	unknownSafety.Safety = "write-enabled"
	if err := unknownSafety.Validate(); err == nil || !strings.Contains(err.Error(), "safety") {
		t.Fatalf("unknown safety validation error = %v", err)
	}

	stateDir := t.TempDir()
	store, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	lock, err := acquireRunnerLock(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	runner := newDaemon(store, newEvidenceStore(stateDir), stateDir)
	if err := runner.Serve(context.Background(), true); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("second daemon error = %v", err)
	}

	unit, err := os.ReadFile(filepath.Join("systemd", "staging-acceptd.service"))
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{"User=staging-accept", "KillMode=control-group", "NoNewPrivileges=true", "UMask=0077"} {
		if !bytes.Contains(unit, []byte(required)) {
			t.Fatalf("systemd unit missing %q", required)
		}
	}

	logFile, err := os.OpenFile(filepath.Join(t.TempDir(), "bounded.log"), os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	bounded := &stageLog{file: logFile, remaining: 40}
	if err := bounded.WriteLine("stdout", strings.Repeat("x", 100)); err != nil {
		t.Fatal(err)
	}
	if err := bounded.Close(); err != nil {
		t.Fatal(err)
	}
	if got := mustRead(t, logFile.Name()); !strings.Contains(got, "[LOG_TRUNCATED]") {
		t.Fatalf("bounded log = %q", got)
	}
}

func TestControlledCanaryRunRequiresExplicitExecuteFlag(t *testing.T) {
	plan := validPlan([]StageSpec{{ID: "execute", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5}})
	plan.Safety = safetyControlledCanary
	plan.SafetyEnvelopeRef = "group-classification-v1"
	planPath := writeTestPlan(t, plan)
	stateDir := t.TempDir()

	var stdout bytes.Buffer
	var stderr bytes.Buffer
	if code := runMain([]string{"run", "--state-dir", stateDir, "--plan", planPath}, &stdout, &stderr); code != 1 {
		t.Fatalf("run without execute flag code = %d, stderr = %q", code, stderr.String())
	}
	if !strings.Contains(stderr.String(), "--execute-canary") {
		t.Fatalf("run without execute flag stderr = %q", stderr.String())
	}

	stdout.Reset()
	stderr.Reset()
	if code := runMain([]string{"run", "--state-dir", stateDir, "--plan", planPath, "--execute-canary"}, &stdout, &stderr); code != 0 {
		t.Fatalf("run with execute flag code = %d, stderr = %q", code, stderr.String())
	}
	if !runIDPattern.MatchString(strings.TrimSpace(stdout.String())) {
		t.Fatalf("run id = %q", stdout.String())
	}
}

func TestCancellationWinsBeforeRunCommit(t *testing.T) {
	stateDir := t.TempDir()
	plan := validPlan([]StageSpec{{ID: "one", Command: []string{"/usr/bin/env", "true"}, TimeoutSeconds: 5}})
	store, _, runID := enqueueTestRun(t, stateDir, plan, "20260825T010101Z-00000005")
	defer store.Close()
	claimed, err := store.ClaimNext(time.Now())
	if err != nil || claimed == nil {
		t.Fatalf("claim = %#v, %v", claimed, err)
	}
	if _, err := store.StartStage(runID, 0, "stages/01-one/attempt-1.log", time.Now()); err != nil {
		t.Fatal(err)
	}
	zero := 0
	if err := store.FinishStage(runID, 0, 1, StagePassed, &zero, "", strings.Repeat("0", 64), time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := store.RequestCancel(runID, time.Now()); err != nil {
		t.Fatal(err)
	}
	status, reason, err := store.FinishRun(runID, RunPassed, "", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if status != RunCancelled || reason != "CANCEL_REQUESTED" {
		t.Fatalf("final = %s/%s", status, reason)
	}
	detail, err := store.Get(runID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.Status != RunCancelled || !detail.Run.CancelRequested {
		t.Fatalf("stored run = %#v", detail.Run)
	}
}

func validPlan(stages []StageSpec) Plan {
	return Plan{
		SchemaVersion: planSchemaVersion,
		Profile:       "test-smoke",
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
}

func writeTestPlan(t *testing.T, plan Plan) string {
	t.Helper()
	data, err := json.MarshalIndent(plan, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "plan.json")
	if err := os.WriteFile(path, append(data, '\n'), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func enqueueTestRun(
	t *testing.T,
	stateDir string,
	plan Plan,
	runID string,
) (*Store, *EvidenceStore, string) {
	t.Helper()
	if err := plan.Validate(); err != nil {
		t.Fatal(err)
	}
	data, err := json.MarshalIndent(plan, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	data = append(data, '\n')
	store, err := openStore(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	evidence := newEvidenceStore(stateDir)
	if err := evidence.InitRun(runID, data); err != nil {
		store.Close()
		t.Fatal(err)
	}
	if err := store.Enqueue(runID, plan, data, time.Now()); err != nil {
		store.Close()
		t.Fatal(err)
	}
	if err := evidence.AppendEvent(eventRecord{RunID: runID, Type: "run_queued", Status: string(RunQueued)}); err != nil {
		store.Close()
		t.Fatal(err)
	}
	return store, evidence, runID
}

func helperDaemonCommand(t *testing.T, stateDir string) *exec.Cmd {
	t.Helper()
	command := exec.Command(os.Args[0], "-test.run=^TestHelperDaemonProcess$")
	command.Env = append(os.Environ(), "STAGING_ACCEPT_HELPER_STATE="+stateDir)
	return command
}

func waitFor(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition not reached before timeout")
}

func mustRead(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func assertTreeExcludes(t *testing.T, root string, needle string) {
	t.Helper()
	err := filepath.WalkDir(root, func(path string, item os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if item.IsDir() {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		if bytes.Contains(data, []byte(needle)) {
			return fmt.Errorf("secret found in %s", path)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func assertTreeContains(t *testing.T, root string, needle string) {
	t.Helper()
	found := false
	_ = filepath.WalkDir(root, func(path string, item os.DirEntry, err error) error {
		if err != nil || item.IsDir() {
			return nil
		}
		data, readErr := os.ReadFile(path)
		if readErr == nil && bytes.Contains(data, []byte(needle)) {
			found = true
		}
		return nil
	})
	if !found {
		t.Fatalf("%q not found under %s", needle, root)
	}
}

func assertMode(t *testing.T, path string, want os.FileMode) {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != want {
		t.Fatalf("mode %s = %o, want %o", path, got, want)
	}
}
