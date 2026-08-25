package main

import (
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

type Store struct {
	db *sql.DB
}

func openStore(stateDir string) (*Store, error) {
	clean, err := filepath.Abs(filepath.Clean(stateDir))
	if err != nil {
		return nil, fmt.Errorf("resolve state directory: %w", err)
	}
	if clean == string(filepath.Separator) {
		return nil, errors.New("state directory cannot be the filesystem root")
	}
	if err := os.MkdirAll(clean, 0o700); err != nil {
		return nil, fmt.Errorf("create state directory: %w", err)
	}
	if err := os.Chmod(clean, 0o700); err != nil {
		return nil, fmt.Errorf("protect state directory: %w", err)
	}
	databasePath := filepath.Join(clean, "runner.db")
	dsnURL := &url.URL{Scheme: "file", Path: databasePath}
	database, err := sql.Open("sqlite3", dsnURL.String()+"?_busy_timeout=5000&_foreign_keys=on&_txlock=immediate")
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	database.SetMaxOpenConns(1)
	store := &Store{db: database}
	if err := store.initialize(); err != nil {
		database.Close()
		return nil, err
	}
	if err := os.Chmod(databasePath, 0o600); err != nil {
		database.Close()
		return nil, fmt.Errorf("protect sqlite database: %w", err)
	}
	return store, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) initialize() error {
	for _, statement := range []string{
		`PRAGMA journal_mode=WAL`,
		`PRAGMA synchronous=NORMAL`,
		`PRAGMA foreign_keys=ON`,
		`CREATE TABLE IF NOT EXISTS runs (
			id TEXT PRIMARY KEY,
			plan_json TEXT NOT NULL,
			status TEXT NOT NULL,
			created_at TEXT NOT NULL,
			started_at TEXT,
			finished_at TEXT,
			heartbeat_at TEXT,
			current_stage INTEGER NOT NULL DEFAULT 0,
			cancel_requested INTEGER NOT NULL DEFAULT 0,
			evidence_ready INTEGER NOT NULL DEFAULT 0,
			failure_reason TEXT NOT NULL DEFAULT ''
		)`,
		`CREATE TABLE IF NOT EXISTS stages (
			run_id TEXT NOT NULL,
			stage_index INTEGER NOT NULL,
			stage_id TEXT NOT NULL,
			status TEXT NOT NULL,
			attempts INTEGER NOT NULL DEFAULT 0,
			started_at TEXT,
			finished_at TEXT,
			exit_code INTEGER,
			reason TEXT NOT NULL DEFAULT '',
			log_path TEXT NOT NULL DEFAULT '',
			sha256 TEXT NOT NULL DEFAULT '',
			PRIMARY KEY (run_id, stage_index),
			FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_runs_status_created ON runs(status, created_at)`,
		`CREATE TABLE IF NOT EXISTS stage_attempts (
			run_id TEXT NOT NULL,
			stage_index INTEGER NOT NULL,
			stage_id TEXT NOT NULL,
			attempt INTEGER NOT NULL,
			status TEXT NOT NULL,
			started_at TEXT NOT NULL,
			finished_at TEXT,
			exit_code INTEGER,
			reason TEXT NOT NULL DEFAULT '',
			log_path TEXT NOT NULL,
			sha256 TEXT NOT NULL DEFAULT '',
			PRIMARY KEY (run_id, stage_index, attempt),
			FOREIGN KEY (run_id, stage_index) REFERENCES stages(run_id, stage_index) ON DELETE CASCADE
		)`,
	} {
		if _, err := s.db.Exec(statement); err != nil {
			return fmt.Errorf("initialize sqlite: %w", err)
		}
	}
	if err := s.ensureEvidenceReadyColumn(); err != nil {
		return err
	}
	return nil
}

func (s *Store) ensureEvidenceReadyColumn() error {
	transaction, err := s.db.Begin()
	if err != nil {
		return fmt.Errorf("begin sqlite schema check: %w", err)
	}
	defer transaction.Rollback()
	rows, err := transaction.Query(`PRAGMA table_info(runs)`)
	if err != nil {
		return fmt.Errorf("inspect runs schema: %w", err)
	}
	found := false
	for rows.Next() {
		var (
			columnID     int
			name         string
			columnType   string
			notNull      int
			defaultValue sql.NullString
			primaryKey   int
		)
		if err := rows.Scan(&columnID, &name, &columnType, &notNull, &defaultValue, &primaryKey); err != nil {
			rows.Close()
			return fmt.Errorf("read runs schema: %w", err)
		}
		if name == "evidence_ready" {
			found = true
		}
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return fmt.Errorf("read runs schema: %w", err)
	}
	if err := rows.Close(); err != nil {
		return fmt.Errorf("close runs schema rows: %w", err)
	}
	if !found {
		if _, err := transaction.Exec(
			`ALTER TABLE runs ADD COLUMN evidence_ready INTEGER NOT NULL DEFAULT 0`,
		); err != nil {
			return fmt.Errorf("add runs evidence readiness: %w", err)
		}
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit sqlite schema check: %w", err)
	}
	return nil
}

func (s *Store) Enqueue(id string, plan Plan, planJSON []byte, now time.Time) error {
	transaction, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	if _, err := transaction.Exec(
		`INSERT INTO runs(id, plan_json, status, created_at) VALUES(?, ?, ?, ?)`,
		id, string(planJSON), RunQueued, formatTime(now),
	); err != nil {
		return fmt.Errorf("insert run: %w", err)
	}
	for index, stage := range plan.Stages {
		if _, err := transaction.Exec(
			`INSERT INTO stages(run_id, stage_index, stage_id, status) VALUES(?, ?, ?, ?)`,
			id, index, stage.ID, StageQueued,
		); err != nil {
			return fmt.Errorf("insert stage: %w", err)
		}
	}
	return transaction.Commit()
}

func (s *Store) ClaimNext(now time.Time) (*RunDetail, error) {
	transaction, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer transaction.Rollback()
	var id string
	err = transaction.QueryRow(
		`SELECT id FROM runs WHERE status = ? ORDER BY created_at, id LIMIT 1`, RunQueued,
	).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("select queued run: %w", err)
	}
	result, err := transaction.Exec(
		`UPDATE runs SET status = ?, started_at = COALESCE(started_at, ?), heartbeat_at = ?, failure_reason = ''
		 WHERE id = ? AND status = ?`,
		RunRunning, formatTime(now), formatTime(now), id, RunQueued,
	)
	if err != nil {
		return nil, fmt.Errorf("claim run: %w", err)
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return nil, err
	}
	if changed != 1 {
		return nil, transaction.Commit()
	}
	if err := transaction.Commit(); err != nil {
		return nil, err
	}
	detail, err := s.Get(id)
	return &detail, err
}

func (s *Store) Get(id string) (RunDetail, error) {
	transaction, err := s.db.Begin()
	if err != nil {
		return RunDetail{}, err
	}
	defer transaction.Rollback()
	run, err := scanRun(transaction.QueryRow(
		`SELECT id, plan_json, status, created_at, started_at, finished_at, heartbeat_at,
		 current_stage, cancel_requested, evidence_ready, failure_reason FROM runs WHERE id = ?`, id,
	))
	if errors.Is(err, sql.ErrNoRows) {
		return RunDetail{}, fmt.Errorf("run %q not found", id)
	}
	if err != nil {
		return RunDetail{}, err
	}
	rows, err := transaction.Query(
		`SELECT stage_index, stage_id, status, attempts, started_at, finished_at,
		 exit_code, reason, log_path, sha256 FROM stages WHERE run_id = ? ORDER BY stage_index`, id,
	)
	if err != nil {
		return RunDetail{}, err
	}
	stages := make([]StageRecord, 0, len(run.Plan.Stages))
	for rows.Next() {
		stage, err := scanStage(rows)
		if err != nil {
			rows.Close()
			return RunDetail{}, err
		}
		stages = append(stages, stage)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return RunDetail{}, err
	}
	if err := rows.Close(); err != nil {
		return RunDetail{}, err
	}
	attemptRows, err := transaction.Query(
		`SELECT stage_index, stage_id, attempt, status, started_at, finished_at,
		 exit_code, reason, log_path, sha256 FROM stage_attempts
		 WHERE run_id = ? ORDER BY stage_index, attempt`, id,
	)
	if err != nil {
		return RunDetail{}, err
	}
	attempts := make([]AttemptRecord, 0)
	for attemptRows.Next() {
		attempt, err := scanAttempt(attemptRows)
		if err != nil {
			attemptRows.Close()
			return RunDetail{}, err
		}
		attempts = append(attempts, attempt)
	}
	if err := attemptRows.Err(); err != nil {
		attemptRows.Close()
		return RunDetail{}, err
	}
	if err := attemptRows.Close(); err != nil {
		return RunDetail{}, err
	}
	if err := transaction.Commit(); err != nil {
		return RunDetail{}, err
	}
	return RunDetail{Run: run, Stages: stages, Attempts: attempts}, nil
}

func (s *Store) List(limit int) ([]RunRecord, error) {
	if limit < 1 || limit > 100 {
		limit = 20
	}
	rows, err := s.db.Query(
		`SELECT id, plan_json, status, created_at, started_at, finished_at, heartbeat_at,
		 current_stage, cancel_requested, evidence_ready, failure_reason
		 FROM runs ORDER BY created_at DESC, id DESC LIMIT ?`, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	runs := make([]RunRecord, 0, limit)
	for rows.Next() {
		run, err := scanRun(rows)
		if err != nil {
			return nil, err
		}
		runs = append(runs, run)
	}
	return runs, rows.Err()
}

func (s *Store) ListTerminalWithoutEvidence() ([]RunRecord, error) {
	rows, err := s.db.Query(
		`SELECT id, plan_json, status, created_at, started_at, finished_at, heartbeat_at,
		 current_stage, cancel_requested, evidence_ready, failure_reason
		 FROM runs WHERE evidence_ready = 0 AND status IN (?, ?, ?)
		 ORDER BY finished_at, id`,
		RunPassed, RunFailed, RunCancelled,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	runs := make([]RunRecord, 0)
	for rows.Next() {
		run, err := scanRun(rows)
		if err != nil {
			return nil, err
		}
		runs = append(runs, run)
	}
	return runs, rows.Err()
}

func (s *Store) StartStage(runID string, index int, logPath string, now time.Time) (int, error) {
	transaction, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer transaction.Rollback()
	result, err := transaction.Exec(
		`UPDATE stages SET status = ?, attempts = attempts + 1, started_at = ?, finished_at = NULL,
		 exit_code = NULL, reason = '', log_path = ?, sha256 = ''
		 WHERE run_id = ? AND stage_index = ? AND status = ?`,
		StageRunning, formatTime(now), logPath, runID, index, StageQueued,
	)
	if err != nil {
		return 0, err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	if changed != 1 {
		return 0, fmt.Errorf("stage %d is not startable", index)
	}
	runResult, err := transaction.Exec(
		`UPDATE runs SET current_stage = ?, heartbeat_at = ? WHERE id = ? AND status = ?`,
		index, formatTime(now), runID, RunRunning,
	)
	if err != nil {
		return 0, err
	}
	runChanged, err := runResult.RowsAffected()
	if err != nil {
		return 0, err
	}
	if runChanged != 1 {
		return 0, fmt.Errorf("run %q is not active", runID)
	}
	var attempts int
	if err := transaction.QueryRow(
		`SELECT attempts FROM stages WHERE run_id = ? AND stage_index = ?`, runID, index,
	).Scan(&attempts); err != nil {
		return 0, err
	}
	var stageID string
	if err := transaction.QueryRow(
		`SELECT stage_id FROM stages WHERE run_id = ? AND stage_index = ?`, runID, index,
	).Scan(&stageID); err != nil {
		return 0, err
	}
	if _, err := transaction.Exec(
		`INSERT INTO stage_attempts(run_id, stage_index, stage_id, attempt, status, started_at, log_path)
		 VALUES(?, ?, ?, ?, ?, ?, ?)`,
		runID, index, stageID, attempts, StageRunning, formatTime(now), logPath,
	); err != nil {
		return 0, err
	}
	if err := transaction.Commit(); err != nil {
		return 0, err
	}
	return attempts, nil
}

func (s *Store) FinishStage(
	runID string,
	index int,
	expectedAttempt int,
	status StageStatus,
	exitCode *int,
	reason string,
	sha256 string,
	now time.Time,
) error {
	transaction, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	result, err := transaction.Exec(
		`UPDATE stages SET status = ?, finished_at = ?, exit_code = ?, reason = ?, sha256 = ?
		 WHERE run_id = ? AND stage_index = ? AND status = ? AND attempts = ?`,
		status, formatTime(now), nullableInt(exitCode), reason, sha256,
		runID, index, StageRunning, expectedAttempt,
	)
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed != 1 {
		return fmt.Errorf("stage %d attempt %d is no longer active", index, expectedAttempt)
	}
	attemptResult, err := transaction.Exec(
		`UPDATE stage_attempts SET status = ?, finished_at = ?, exit_code = ?, reason = ?, sha256 = ?
		 WHERE run_id = ? AND stage_index = ? AND attempt = ? AND status = ?`,
		status, formatTime(now), nullableInt(exitCode), reason, sha256,
		runID, index, expectedAttempt, StageRunning,
	)
	if err != nil {
		return err
	}
	attemptChanged, err := attemptResult.RowsAffected()
	if err != nil {
		return err
	}
	if attemptChanged != 1 {
		return fmt.Errorf("attempt record %d/%d is no longer active", index, expectedAttempt)
	}
	return transaction.Commit()
}

func (s *Store) Touch(runID string, now time.Time) error {
	_, err := s.db.Exec(
		`UPDATE runs SET heartbeat_at = ? WHERE id = ? AND status = ?`,
		formatTime(now), runID, RunRunning,
	)
	return err
}

func (s *Store) CancelRequested(runID string) (bool, error) {
	var requested int
	if err := s.db.QueryRow(`SELECT cancel_requested FROM runs WHERE id = ?`, runID).Scan(&requested); err != nil {
		return false, err
	}
	return requested != 0, nil
}

func (s *Store) FinishRun(runID string, status RunStatus, reason string, now time.Time) (RunStatus, string, error) {
	if !isTerminalRun(status) {
		return "", "", fmt.Errorf("cannot finish run with status %s", status)
	}
	transaction, err := s.db.Begin()
	if err != nil {
		return "", "", err
	}
	defer transaction.Rollback()
	var (
		current   RunStatus
		cancelled int
	)
	if err := transaction.QueryRow(
		`SELECT status, cancel_requested FROM runs WHERE id = ?`, runID,
	).Scan(&current, &cancelled); err != nil {
		return "", "", err
	}
	if current != RunRunning {
		return "", "", fmt.Errorf("run %q is no longer active", runID)
	}
	if cancelled != 0 {
		status = RunCancelled
		reason = "CANCEL_REQUESTED"
		if _, err := transaction.Exec(
			`UPDATE stages SET status = ?, finished_at = ?, reason = ? WHERE run_id = ? AND status = ?`,
			StageCancelled, formatTime(now), reason, runID, StageQueued,
		); err != nil {
			return "", "", err
		}
	}
	result, err := transaction.Exec(
		`UPDATE runs SET status = ?, finished_at = ?, heartbeat_at = ?, evidence_ready = 0, failure_reason = ?
		 WHERE id = ? AND status = ?`,
		status, formatTime(now), formatTime(now), reason, runID, RunRunning,
	)
	if err != nil {
		return "", "", err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return "", "", err
	}
	if changed != 1 {
		return "", "", fmt.Errorf("run %q is no longer active", runID)
	}
	if err := transaction.Commit(); err != nil {
		return "", "", err
	}
	return status, reason, nil
}

func (s *Store) MarkEvidenceReady(runID string, expectedStatus RunStatus) error {
	if !isTerminalRun(expectedStatus) {
		return fmt.Errorf("cannot mark evidence ready for non-terminal status %s", expectedStatus)
	}
	transaction, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	var (
		status RunStatus
		ready  int
	)
	if err := transaction.QueryRow(
		`SELECT status, evidence_ready FROM runs WHERE id = ?`, runID,
	).Scan(&status, &ready); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return fmt.Errorf("run %q not found", runID)
		}
		return err
	}
	if status != expectedStatus {
		return fmt.Errorf("run %q is %s, expected %s before marking evidence ready", runID, status, expectedStatus)
	}
	if ready != 0 {
		return transaction.Commit()
	}
	result, err := transaction.Exec(
		`UPDATE runs SET evidence_ready = 1 WHERE id = ? AND status = ? AND evidence_ready = 0`,
		runID, expectedStatus,
	)
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed != 1 {
		return fmt.Errorf("run %q changed while marking evidence ready", runID)
	}
	return transaction.Commit()
}

func (s *Store) SetRecoveredAttemptEvidence(
	runID string,
	stageIndex int,
	attempt int,
	sha256 string,
	evidenceIncompleteReason string,
) error {
	if stageIndex < 0 || attempt < 1 {
		return errors.New("recovered stage index and attempt must be valid")
	}
	hasHash := sha256 != ""
	hasIncompleteReason := evidenceIncompleteReason != ""
	if hasHash == hasIncompleteReason {
		return errors.New("provide exactly one of sha256 or evidence-incomplete reason")
	}
	if hasHash {
		decoded, err := hex.DecodeString(sha256)
		if err != nil || len(decoded) != 32 {
			return errors.New("recovered evidence sha256 must be 64 hexadecimal characters")
		}
	}
	transaction, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	var stageResult, attemptResult sql.Result
	if hasHash {
		stageResult, err = transaction.Exec(
			`UPDATE stages SET sha256 = ?
			 WHERE run_id = ? AND stage_index = ? AND attempts = ?
			 AND status IN (?, ?) AND (sha256 = '' OR sha256 = ?)`,
			sha256, runID, stageIndex, attempt, StageInterrupted, StageCancelled, sha256,
		)
		if err == nil {
			attemptResult, err = transaction.Exec(
				`UPDATE stage_attempts SET sha256 = ?
				 WHERE run_id = ? AND stage_index = ? AND attempt = ?
				 AND status IN (?, ?) AND (sha256 = '' OR sha256 = ?)`,
				sha256, runID, stageIndex, attempt, StageInterrupted, StageCancelled, sha256,
			)
		}
	} else {
		stageResult, err = transaction.Exec(
			`UPDATE stages SET reason = CASE
			 WHEN reason = '' THEN ?
			 WHEN instr(reason, ?) > 0 THEN reason
			 ELSE reason || ';' || ? END
			 WHERE run_id = ? AND stage_index = ? AND attempts = ? AND status IN (?, ?)`,
			evidenceIncompleteReason, evidenceIncompleteReason, evidenceIncompleteReason,
			runID, stageIndex, attempt, StageInterrupted, StageCancelled,
		)
		if err == nil {
			attemptResult, err = transaction.Exec(
				`UPDATE stage_attempts SET reason = CASE
				 WHEN reason = '' THEN ?
				 WHEN instr(reason, ?) > 0 THEN reason
				 ELSE reason || ';' || ? END
				 WHERE run_id = ? AND stage_index = ? AND attempt = ? AND status IN (?, ?)`,
				evidenceIncompleteReason, evidenceIncompleteReason, evidenceIncompleteReason,
				runID, stageIndex, attempt, StageInterrupted, StageCancelled,
			)
		}
	}
	if err != nil {
		return err
	}
	stageChanged, err := stageResult.RowsAffected()
	if err != nil {
		return err
	}
	attemptChanged, err := attemptResult.RowsAffected()
	if err != nil {
		return err
	}
	if stageChanged != 1 || attemptChanged != 1 {
		return fmt.Errorf("recovered attempt %s/%d/%d is not conditionally writable", runID, stageIndex, attempt)
	}
	return transaction.Commit()
}

func (s *Store) RecoverInterrupted(now time.Time) ([]string, error) {
	transaction, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer transaction.Rollback()
	rows, err := transaction.Query(
		`SELECT id, cancel_requested FROM runs WHERE status = ? ORDER BY created_at, id`, RunRunning,
	)
	if err != nil {
		return nil, err
	}
	type interruptedRun struct {
		id              string
		cancelRequested bool
	}
	var runs []interruptedRun
	for rows.Next() {
		var (
			runID     string
			cancelled int
		)
		if err := rows.Scan(&runID, &cancelled); err != nil {
			rows.Close()
			return nil, err
		}
		runs = append(runs, interruptedRun{id: runID, cancelRequested: cancelled != 0})
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	runIDs := make([]string, 0, len(runs))
	for _, run := range runs {
		runStatus := RunFailed
		stageStatus := StageInterrupted
		reason := "RUNNER_INTERRUPTED"
		if run.cancelRequested {
			runStatus = RunCancelled
			stageStatus = StageCancelled
			reason = "CANCEL_REQUESTED"
		}
		if _, err := transaction.Exec(
			`UPDATE stages SET status = ?, finished_at = ?, exit_code = NULL, reason = ?
			 WHERE run_id = ? AND status = ?`,
			stageStatus, formatTime(now), reason, run.id, StageRunning,
		); err != nil {
			return nil, err
		}
		if _, err := transaction.Exec(
			`UPDATE stage_attempts SET status = ?, finished_at = ?, exit_code = NULL, reason = ?
			 WHERE run_id = ? AND status = ?`,
			stageStatus, formatTime(now), reason, run.id, StageRunning,
		); err != nil {
			return nil, err
		}
		if run.cancelRequested {
			if _, err := transaction.Exec(
				`UPDATE stages SET status = ?, finished_at = ?, reason = ?
				 WHERE run_id = ? AND status = ?`,
				StageCancelled, formatTime(now), reason, run.id, StageQueued,
			); err != nil {
				return nil, err
			}
		}
		result, err := transaction.Exec(
			`UPDATE runs SET status = ?, finished_at = ?, heartbeat_at = ?, evidence_ready = 0,
			 failure_reason = ? WHERE id = ? AND status = ?`,
			runStatus, formatTime(now), formatTime(now), reason, run.id, RunRunning,
		)
		if err != nil {
			return nil, err
		}
		changed, err := result.RowsAffected()
		if err != nil {
			return nil, err
		}
		if changed != 1 {
			return nil, fmt.Errorf("run %q changed while recovering", run.id)
		}
		runIDs = append(runIDs, run.id)
	}
	if err := transaction.Commit(); err != nil {
		return nil, err
	}
	return runIDs, nil
}

func (s *Store) RequestCancel(runID string, now time.Time) (RunStatus, error) {
	transaction, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer transaction.Rollback()
	var status RunStatus
	if err := transaction.QueryRow(`SELECT status FROM runs WHERE id = ?`, runID).Scan(&status); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", fmt.Errorf("run %q not found", runID)
		}
		return "", err
	}
	switch status {
	case RunQueued:
		if _, err := transaction.Exec(
			`UPDATE runs SET status = ?, cancel_requested = 1, evidence_ready = 0,
			 finished_at = ?, failure_reason = ? WHERE id = ?`,
			RunCancelled, formatTime(now), "CANCELLED_BEFORE_START", runID,
		); err != nil {
			return "", err
		}
		if _, err := transaction.Exec(
			`UPDATE stages SET status = ?, finished_at = ?, reason = ? WHERE run_id = ? AND status = ?`,
			StageCancelled, formatTime(now), "CANCELLED_BEFORE_START", runID, StageQueued,
		); err != nil {
			return "", err
		}
		status = RunCancelled
	case RunRunning:
		if _, err := transaction.Exec(`UPDATE runs SET cancel_requested = 1 WHERE id = ?`, runID); err != nil {
			return "", err
		}
	}
	return status, transaction.Commit()
}

func (s *Store) Resume(runID string) error {
	transaction, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	var (
		status        RunStatus
		evidenceReady int
	)
	if err := transaction.QueryRow(
		`SELECT status, evidence_ready FROM runs WHERE id = ?`, runID,
	).Scan(&status, &evidenceReady); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return fmt.Errorf("run %q not found", runID)
		}
		return err
	}
	if status != RunFailed {
		return fmt.Errorf("run %q is %s; only FAIL can be resumed", runID, status)
	}
	if evidenceReady == 0 {
		return fmt.Errorf("run %q evidence is not ready; finalize evidence before resume", runID)
	}
	if _, err := transaction.Exec(
		`UPDATE stages SET status = ?, started_at = NULL, finished_at = NULL, exit_code = NULL,
		 reason = '', log_path = '', sha256 = '' WHERE run_id = ? AND status <> ?`,
		StageQueued, runID, StagePassed,
	); err != nil {
		return err
	}
	var next int
	if err := transaction.QueryRow(
		`SELECT COALESCE(MIN(stage_index), 0) FROM stages WHERE run_id = ? AND status <> ?`,
		runID, StagePassed,
	).Scan(&next); err != nil {
		return err
	}
	result, err := transaction.Exec(
		`UPDATE runs SET status = ?, finished_at = NULL, heartbeat_at = NULL, current_stage = ?,
		 cancel_requested = 0, evidence_ready = 0, failure_reason = ''
		 WHERE id = ? AND status = ? AND evidence_ready = 1`,
		RunQueued, next, runID, RunFailed,
	)
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed != 1 {
		return fmt.Errorf("run %q changed while resuming", runID)
	}
	return transaction.Commit()
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanRun(scanner rowScanner) (RunRecord, error) {
	var (
		run       RunRecord
		planJSON  string
		created   string
		started   sql.NullString
		finished  sql.NullString
		heartbeat sql.NullString
		cancelled int
		evidence  int
	)
	if err := scanner.Scan(
		&run.ID, &planJSON, &run.Status, &created, &started, &finished, &heartbeat,
		&run.CurrentStage, &cancelled, &evidence, &run.FailureReason,
	); err != nil {
		return RunRecord{}, err
	}
	if err := json.Unmarshal([]byte(planJSON), &run.Plan); err != nil {
		return RunRecord{}, fmt.Errorf("decode stored plan: %w", err)
	}
	var err error
	if run.CreatedAt, err = parseTime(created); err != nil {
		return RunRecord{}, err
	}
	if run.StartedAt, err = parseNullableTime(started); err != nil {
		return RunRecord{}, err
	}
	if run.FinishedAt, err = parseNullableTime(finished); err != nil {
		return RunRecord{}, err
	}
	if run.HeartbeatAt, err = parseNullableTime(heartbeat); err != nil {
		return RunRecord{}, err
	}
	run.CancelRequested = cancelled != 0
	run.EvidenceReady = evidence != 0
	return run, nil
}

func scanStage(scanner rowScanner) (StageRecord, error) {
	var (
		stage    StageRecord
		started  sql.NullString
		finished sql.NullString
		exitCode sql.NullInt64
	)
	if err := scanner.Scan(
		&stage.Index, &stage.ID, &stage.Status, &stage.Attempts, &started, &finished,
		&exitCode, &stage.Reason, &stage.LogPath, &stage.SHA256,
	); err != nil {
		return StageRecord{}, err
	}
	var err error
	if stage.StartedAt, err = parseNullableTime(started); err != nil {
		return StageRecord{}, err
	}
	if stage.FinishedAt, err = parseNullableTime(finished); err != nil {
		return StageRecord{}, err
	}
	if exitCode.Valid {
		value := int(exitCode.Int64)
		stage.ExitCode = &value
	}
	return stage, nil
}

func scanAttempt(scanner rowScanner) (AttemptRecord, error) {
	var (
		attempt  AttemptRecord
		started  string
		finished sql.NullString
		exitCode sql.NullInt64
	)
	if err := scanner.Scan(
		&attempt.StageIndex, &attempt.StageID, &attempt.Attempt, &attempt.Status,
		&started, &finished, &exitCode, &attempt.Reason, &attempt.LogPath, &attempt.SHA256,
	); err != nil {
		return AttemptRecord{}, err
	}
	var err error
	if attempt.StartedAt, err = parseTime(started); err != nil {
		return AttemptRecord{}, err
	}
	if attempt.FinishedAt, err = parseNullableTime(finished); err != nil {
		return AttemptRecord{}, err
	}
	if exitCode.Valid {
		value := int(exitCode.Int64)
		attempt.ExitCode = &value
	}
	return attempt, nil
}

func formatTime(value time.Time) string {
	return value.UTC().Format(time.RFC3339Nano)
}

func parseTime(value string) (time.Time, error) {
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return time.Time{}, fmt.Errorf("parse stored time: %w", err)
	}
	return parsed.UTC(), nil
}

func parseNullableTime(value sql.NullString) (*time.Time, error) {
	if !value.Valid {
		return nil, nil
	}
	parsed, err := parseTime(value.String)
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}

func nullableInt(value *int) any {
	if value == nil {
		return nil
	}
	return *value
}
