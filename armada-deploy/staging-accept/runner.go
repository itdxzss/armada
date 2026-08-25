package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

var errDaemonStopped = errors.New("runner daemon stopped")

type daemon struct {
	store             *Store
	evidence          *EvidenceStore
	stateDir          string
	pollInterval      time.Duration
	heartbeatInterval time.Duration
	terminateGrace    time.Duration
	now               func() time.Time
}

type stageOutcome struct {
	stageStatus StageStatus
	runStatus   RunStatus
	exitCode    *int
	reason      string
	stopped     bool
}

type processResult struct {
	exitCode int
	err      error
}

func newDaemon(store *Store, evidence *EvidenceStore, stateDir string) *daemon {
	return &daemon{
		store:             store,
		evidence:          evidence,
		stateDir:          stateDir,
		pollInterval:      time.Second,
		heartbeatInterval: time.Second,
		terminateGrace:    2 * time.Second,
		now:               time.Now,
	}
}

func (d *daemon) Serve(ctx context.Context, once bool) error {
	lock, err := acquireRunnerLock(d.stateDir)
	if err != nil {
		return err
	}
	defer lock.Close()

	interrupted, err := d.store.RecoverInterrupted(d.now())
	if err != nil {
		return fmt.Errorf("recover interrupted runs: %w", err)
	}
	for _, runID := range interrupted {
		if err := d.captureRecoveredAttemptEvidence(runID); err != nil {
			return err
		}
		detail, err := d.store.Get(runID)
		if err != nil {
			return err
		}
		if err := d.evidence.AppendEvent(eventRecord{
			RunID: runID, Type: "run_recovered", Status: string(detail.Run.Status), Reason: detail.Run.FailureReason,
		}); err != nil {
			return err
		}
		if err := d.finalize(runID, detail.Run.FailureReason); err != nil {
			return err
		}
	}

	for {
		if err := ctx.Err(); err != nil {
			return nil
		}
		if err := d.finalizePendingTerminals(); err != nil {
			return err
		}
		next, err := d.store.ClaimNext(d.now())
		if err != nil {
			return err
		}
		if next == nil {
			if once {
				return nil
			}
			timer := time.NewTimer(d.pollInterval)
			select {
			case <-ctx.Done():
				timer.Stop()
				return nil
			case <-timer.C:
			}
			continue
		}
		if err := d.executeRun(ctx, *next); err != nil {
			if errors.Is(err, errDaemonStopped) && ctx.Err() != nil {
				return nil
			}
			return err
		}
	}
}

func (d *daemon) executeRun(ctx context.Context, detail RunDetail) error {
	runID := detail.Run.ID
	if err := d.evidence.AppendEvent(eventRecord{
		RunID: runID, Type: "run_started", Status: string(RunRunning),
	}); err != nil {
		return err
	}

	for index, stage := range detail.Stages {
		if stage.Status == StagePassed {
			continue
		}
		if ctx.Err() != nil {
			return d.stopBeforeStage(detail)
		}
		cancelled, err := d.store.CancelRequested(runID)
		if err != nil {
			return err
		}
		if cancelled {
			_, finalReason, err := d.store.FinishRun(runID, RunCancelled, "CANCEL_REQUESTED", d.now())
			if err != nil {
				return err
			}
			return d.finalize(runID, finalReason)
		}

		attempt := stage.Attempts + 1
		log, relativeLog, err := d.evidence.OpenStageLog(runID, index, stage.ID, attempt)
		if err != nil {
			return err
		}
		actualAttempt, err := d.store.StartStage(runID, index, relativeLog, d.now())
		if err != nil {
			closeErr := log.Close()
			cleanupErr := d.evidence.RemoveUntrackedStageLog(runID, relativeLog)
			return fmt.Errorf("start stage %s: %w", stage.ID, errors.Join(err, closeErr, cleanupErr))
		}
		if actualAttempt != attempt {
			log.Close()
			return fmt.Errorf("unexpected attempt number for stage %s", stage.ID)
		}
		if err := d.evidence.AppendEvent(eventRecord{
			RunID: runID, Type: "stage_started", StageID: stage.ID,
			Status: string(StageRunning), Attempt: attempt,
		}); err != nil {
			log.Close()
			return err
		}

		outcome := d.executeStage(ctx, runID, detail.Run.Plan.Stages[index], log)
		if err := log.Close(); err != nil {
			return err
		}
		runDirectory, err := d.evidence.runDir(runID)
		if err != nil {
			return err
		}
		logHash, err := hashFile(filepath.Join(runDirectory, filepath.FromSlash(relativeLog)))
		if err != nil {
			return err
		}
		if err := d.store.FinishStage(
			runID, index, attempt, outcome.stageStatus, outcome.exitCode, outcome.reason, logHash, d.now(),
		); err != nil {
			return err
		}
		if err := d.evidence.AppendEvent(eventRecord{
			RunID: runID, Type: "stage_finished", StageID: stage.ID,
			Status: string(outcome.stageStatus), Reason: outcome.reason, Attempt: attempt,
		}); err != nil {
			return err
		}

		if outcome.stageStatus != StagePassed {
			_, finalReason, err := d.store.FinishRun(runID, outcome.runStatus, outcome.reason, d.now())
			if err != nil {
				return err
			}
			if err := d.finalize(runID, finalReason); err != nil {
				return err
			}
			if outcome.stopped {
				return errDaemonStopped
			}
			return nil
		}
	}

	_, finalReason, err := d.store.FinishRun(runID, RunPassed, "", d.now())
	if err != nil {
		return err
	}
	return d.finalize(runID, finalReason)
}

func (d *daemon) stopBeforeStage(detail RunDetail) error {
	runID := detail.Run.ID
	_, finalReason, err := d.store.FinishRun(runID, RunFailed, "RUNNER_STOPPED", d.now())
	if err != nil {
		return err
	}
	if err := d.finalize(runID, finalReason); err != nil {
		return err
	}
	return errDaemonStopped
}

func (d *daemon) executeStage(
	ctx context.Context,
	runID string,
	stage StageSpec,
	log *stageLog,
) stageOutcome {
	command, done, err := startProcess(stage, log)
	if err != nil {
		_ = log.WriteLine("runner", "command start failed")
		return stageOutcome{stageStatus: StageFailed, runStatus: RunFailed, reason: "START_FAILED"}
	}
	timeout := time.NewTimer(time.Duration(stage.TimeoutSeconds) * time.Second)
	defer timeout.Stop()
	heartbeat := time.NewTicker(d.heartbeatInterval)
	defer heartbeat.Stop()

	for {
		select {
		case result := <-done:
			cancelled, cancelErr := d.store.CancelRequested(runID)
			if cancelErr != nil {
				return stageOutcome{stageStatus: StageFailed, runStatus: RunFailed, reason: "STATE_READ_FAILED"}
			}
			if cancelled {
				return stageOutcome{stageStatus: StageCancelled, runStatus: RunCancelled, reason: "CANCEL_REQUESTED"}
			}
			if result.err == nil {
				code := 0
				return stageOutcome{stageStatus: StagePassed, runStatus: RunPassed, exitCode: &code}
			}
			code := result.exitCode
			return stageOutcome{
				stageStatus: StageFailed, runStatus: RunFailed, exitCode: &code, reason: "EXIT_NON_ZERO",
			}
		case <-timeout.C:
			_ = log.WriteLine("runner", "stage timeout reached")
			terminateProcess(command, done, d.terminateGrace)
			return stageOutcome{stageStatus: StageTimedOut, runStatus: RunFailed, reason: "STAGE_TIMEOUT"}
		case <-ctx.Done():
			_ = log.WriteLine("runner", "runner stopping")
			terminateProcess(command, done, d.terminateGrace)
			return stageOutcome{
				stageStatus: StageInterrupted, runStatus: RunFailed, reason: "RUNNER_STOPPED", stopped: true,
			}
		case <-heartbeat.C:
			if err := d.store.Touch(runID, d.now()); err != nil {
				terminateProcess(command, done, d.terminateGrace)
				return stageOutcome{stageStatus: StageFailed, runStatus: RunFailed, reason: "STATE_WRITE_FAILED"}
			}
			cancelled, err := d.store.CancelRequested(runID)
			if err != nil {
				terminateProcess(command, done, d.terminateGrace)
				return stageOutcome{stageStatus: StageFailed, runStatus: RunFailed, reason: "STATE_READ_FAILED"}
			}
			if cancelled {
				_ = log.WriteLine("runner", "cancellation requested")
				terminateProcess(command, done, d.terminateGrace)
				return stageOutcome{stageStatus: StageCancelled, runStatus: RunCancelled, reason: "CANCEL_REQUESTED"}
			}
		}
	}
}

func (d *daemon) finalize(runID string, reason string) error {
	detail, err := d.store.Get(runID)
	if err != nil {
		return err
	}
	if err := d.evidence.AppendEvent(eventRecord{
		RunID: runID, Type: "run_finished", Status: string(detail.Run.Status), Reason: reason,
	}); err != nil {
		return err
	}
	detail, err = d.store.Get(runID)
	if err != nil {
		return err
	}
	if _, err = d.evidence.Finalize(detail); err != nil {
		return err
	}
	return d.store.MarkEvidenceReady(runID, detail.Run.Status)
}

func (d *daemon) captureRecoveredAttemptEvidence(runID string) error {
	detail, err := d.store.Get(runID)
	if err != nil {
		return err
	}
	for _, attempt := range detail.Attempts {
		if attempt.SHA256 != "" || (attempt.Status != StageInterrupted && attempt.Status != StageCancelled) {
			continue
		}
		hash, hashErr := d.evidence.HashStageLog(runID, attempt.LogPath)
		if hashErr != nil {
			if err := d.store.SetRecoveredAttemptEvidence(
				runID, attempt.StageIndex, attempt.Attempt, "", "EVIDENCE_INCOMPLETE:LOG_UNAVAILABLE",
			); err != nil {
				return err
			}
			continue
		}
		if err := d.store.SetRecoveredAttemptEvidence(
			runID, attempt.StageIndex, attempt.Attempt, hash, "",
		); err != nil {
			return err
		}
	}
	return nil
}

func (d *daemon) finalizePendingTerminals() error {
	runs, err := d.store.ListTerminalWithoutEvidence()
	if err != nil {
		return err
	}
	for _, run := range runs {
		if err := d.captureRecoveredAttemptEvidence(run.ID); err != nil {
			return err
		}
		detail, err := d.store.Get(run.ID)
		if err != nil {
			return err
		}
		if _, err := d.evidence.VerifiedReport(detail); err == nil {
			if err := d.store.MarkEvidenceReady(run.ID, detail.Run.Status); err != nil {
				return err
			}
			continue
		}
		if err := d.evidence.AppendEvent(eventRecord{
			RunID: run.ID, Type: "run_finalization_recovered",
			Status: string(detail.Run.Status), Reason: detail.Run.FailureReason,
		}); err != nil {
			return err
		}
		detail, err = d.store.Get(run.ID)
		if err != nil {
			return err
		}
		if _, err := d.evidence.Finalize(detail); err != nil {
			return err
		}
		if err := d.store.MarkEvidenceReady(run.ID, detail.Run.Status); err != nil {
			return err
		}
	}
	return nil
}

func startProcess(stage StageSpec, log *stageLog) (*exec.Cmd, <-chan processResult, error) {
	command := exec.Command(stage.Command[0], stage.Command[1:]...)
	command.Dir = stage.WorkingDirectory
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, nil, err
	}
	stderr, err := command.StderrPipe()
	if err != nil {
		return nil, nil, err
	}
	if err := command.Start(); err != nil {
		return nil, nil, err
	}
	var readers sync.WaitGroup
	readers.Add(2)
	go func() {
		defer readers.Done()
		scanOutput(stdout, "stdout", log)
	}()
	go func() {
		defer readers.Done()
		scanOutput(stderr, "stderr", log)
	}()
	done := make(chan processResult, 1)
	go func() {
		readers.Wait()
		err := command.Wait()
		exitCode := 0
		if err != nil {
			exitCode = -1
			var exitError *exec.ExitError
			if errors.As(err, &exitError) {
				exitCode = exitError.ExitCode()
			}
		}
		done <- processResult{exitCode: exitCode, err: err}
	}()
	return command, done, nil
}

func terminateProcess(command *exec.Cmd, done <-chan processResult, grace time.Duration) {
	if command == nil || command.Process == nil {
		return
	}
	_ = syscall.Kill(-command.Process.Pid, syscall.SIGTERM)
	timer := time.NewTimer(grace)
	defer timer.Stop()
	select {
	case <-done:
		return
	case <-timer.C:
	}
	_ = syscall.Kill(-command.Process.Pid, syscall.SIGKILL)
	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}
}

type runnerLock struct {
	file *os.File
}

func acquireRunnerLock(stateDir string) (*runnerLock, error) {
	path := filepath.Join(stateDir, "runner.lock")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		file.Close()
		return nil, errors.New("another staging-accept daemon is already running")
	}
	return &runnerLock{file: file}, nil
}

func (l *runnerLock) Close() error {
	if l == nil || l.file == nil {
		return nil
	}
	_ = syscall.Flock(int(l.file.Fd()), syscall.LOCK_UN)
	return l.file.Close()
}
