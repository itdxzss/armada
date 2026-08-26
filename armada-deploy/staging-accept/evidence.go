package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxStageLogBytes int64 = 10 * 1024 * 1024

var (
	runIDPattern          = regexp.MustCompile(`^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$`)
	attemptLogPathPattern = regexp.MustCompile(`^stages/[0-9]{2}-[a-z][a-z0-9-]{0,63}/attempt-[1-9][0-9]*\.log$`)
	redactionPatterns     = []struct {
		pattern     *regexp.Regexp
		replacement string
	}{
		{regexp.MustCompile(`(?i)("(?:password|passwd|token|access[-_]?token|refresh[-_]?token|secret|client[-_]?secret|api[-_]?key|authorization|cookie|creds|credentials)"\s*:\s*")((?:\\.|[^"\\])*)(")`), `${1}[REDACTED]${3}`},
		{regexp.MustCompile(`(?i)(set-cookie\s*:\s*)([^\r\n]*)`), `${1}[REDACTED]`},
		{regexp.MustCompile(`(?i)(authorization\s*[:=]\s*(?:bearer\s+)?)([^\s,;]+)`), `${1}[REDACTED]`},
		{regexp.MustCompile(`(?i)(bearer\s+)([a-z0-9._~+/=-]+)`), `${1}[REDACTED]`},
		{regexp.MustCompile(`(?i)((?:password|passwd|token|access[-_]?token|refresh[-_]?token|secret|client[-_]?secret|api[-_]?key|authorization|cookie|creds|credentials)\s*[:=]\s*)([^\s,;]+)`), `${1}[REDACTED]`},
		{regexp.MustCompile(`(?i)\+?[0-9]{8,15}@(s\.whatsapp\.net|lid|g\.us)`), `[JID_REDACTED]`},
		{regexp.MustCompile(`\+[0-9][0-9 ()-]{7,18}[0-9]`), `[PHONE_REDACTED]`},
	}
)

type EvidenceStore struct {
	stateDir string
}

type eventRecord struct {
	At      string `json:"at"`
	RunID   string `json:"runId"`
	Type    string `json:"type"`
	StageID string `json:"stageId,omitempty"`
	Status  string `json:"status,omitempty"`
	Reason  string `json:"reason,omitempty"`
	Attempt int    `json:"attempt,omitempty"`
}

type safeSummary struct {
	SchemaVersion     int             `json:"schemaVersion"`
	RunID             string          `json:"runId"`
	Profile           string          `json:"profile"`
	Environment       string          `json:"environment"`
	Safety            string          `json:"safety"`
	SafetyEnvelopeRef string          `json:"safetyEnvelopeRef,omitempty"`
	Status            RunStatus       `json:"status"`
	Builds            BuildManifest   `json:"builds"`
	CreatedAt         time.Time       `json:"createdAt"`
	StartedAt         *time.Time      `json:"startedAt,omitempty"`
	FinishedAt        *time.Time      `json:"finishedAt,omitempty"`
	FailureReason     string          `json:"failureReason,omitempty"`
	Stages            []StageRecord   `json:"stages"`
	Attempts          []AttemptRecord `json:"attempts"`
}

func newEvidenceStore(stateDir string) *EvidenceStore {
	return &EvidenceStore{stateDir: stateDir}
}

func (e *EvidenceStore) InitRun(runID string, planJSON []byte) error {
	directory, err := e.runDir(runID)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("create run evidence directory: %w", err)
	}
	if err := os.Chmod(directory, 0o700); err != nil {
		return err
	}
	if err := writeAtomic(filepath.Join(directory, "plan.json"), planJSON, 0o600); err != nil {
		return err
	}
	eventsPath := filepath.Join(directory, "events.ndjson")
	file, err := os.OpenFile(eventsPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	return file.Close()
}

func (e *EvidenceStore) AppendEvent(record eventRecord) error {
	directory, err := e.runDir(record.RunID)
	if err != nil {
		return err
	}
	record.At = formatTime(time.Now())
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	file, err := os.OpenFile(
		filepath.Join(directory, "events.ndjson"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600,
	)
	if err != nil {
		return err
	}
	defer file.Close()
	if _, err := file.Write(append(data, '\n')); err != nil {
		return err
	}
	return file.Sync()
}

func (e *EvidenceStore) OpenStageLog(
	runID string,
	index int,
	stageID string,
	attempt int,
) (*stageLog, string, error) {
	directory, err := e.runDir(runID)
	if err != nil {
		return nil, "", err
	}
	relative := filepath.Join("stages", fmt.Sprintf("%02d-%s", index+1, stageID), fmt.Sprintf("attempt-%d.log", attempt))
	path := filepath.Join(directory, relative)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, "", err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if errors.Is(err, os.ErrExist) {
		return nil, "", fmt.Errorf("attempt log already exists: %s", relative)
	}
	if err != nil {
		return nil, "", err
	}
	return &stageLog{file: file, remaining: maxStageLogBytes}, filepath.ToSlash(relative), nil
}

func (e *EvidenceStore) Finalize(detail RunDetail) (string, error) {
	directory, err := e.runDir(detail.Run.ID)
	if err != nil {
		return "", err
	}
	if err := e.verifyAttemptEvidence(detail); err != nil {
		return "", err
	}
	data, err := marshalSummary(detail)
	if err != nil {
		return "", err
	}
	if err := writeAtomic(filepath.Join(directory, "summary.json"), data, 0o600); err != nil {
		return "", err
	}
	manifestPath := filepath.Join(directory, "checksums.sha256")
	if err := writeChecksums(directory, manifestPath); err != nil {
		return "", err
	}
	return writeReport(directory, detail)
}

func marshalSummary(detail RunDetail) ([]byte, error) {
	summary := safeSummary{
		SchemaVersion:     planSchemaVersion,
		RunID:             detail.Run.ID,
		Profile:           detail.Run.Plan.Profile,
		Environment:       detail.Run.Plan.Environment,
		Safety:            detail.Run.Plan.Safety,
		SafetyEnvelopeRef: detail.Run.Plan.SafetyEnvelopeRef,
		Status:            detail.Run.Status,
		Builds:            detail.Run.Plan.Builds,
		CreatedAt:         detail.Run.CreatedAt,
		StartedAt:         detail.Run.StartedAt,
		FinishedAt:        detail.Run.FinishedAt,
		FailureReason:     detail.Run.FailureReason,
		Stages:            detail.Stages,
		Attempts:          detail.Attempts,
	}
	data, err := json.MarshalIndent(summary, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(data, '\n'), nil
}

// VerifiedReport rebuilds the human-readable report only after the existing
// evidence manifest has been verified. It never creates or rewrites the
// manifest, so a report request cannot bless modified evidence.
func (e *EvidenceStore) VerifiedReport(detail RunDetail) (string, error) {
	directory, err := e.runDir(detail.Run.ID)
	if err != nil {
		return "", err
	}
	if err := verifyChecksums(directory); err != nil {
		return "", fmt.Errorf("verify evidence checksums: %w", err)
	}
	if err := e.verifyAttemptEvidence(detail); err != nil {
		return "", err
	}
	expectedSummary, err := marshalSummary(detail)
	if err != nil {
		return "", err
	}
	storedSummary, err := os.ReadFile(filepath.Join(directory, "summary.json"))
	if err != nil {
		return "", fmt.Errorf("read evidence summary: %w", err)
	}
	if !bytes.Equal(storedSummary, expectedSummary) {
		return "", errors.New("verified evidence summary does not match durable run state")
	}
	return writeReport(directory, detail)
}

func (e *EvidenceStore) verifyAttemptEvidence(detail RunDetail) error {
	latest := make(map[int]AttemptRecord, len(detail.Stages))
	for _, attempt := range detail.Attempts {
		if attempt.Status == StageRunning || attempt.Status == StageQueued {
			return fmt.Errorf("attempt %s/%d is not terminal", attempt.StageID, attempt.Attempt)
		}
		if attempt.SHA256 == "" {
			if !strings.Contains(attempt.Reason, "EVIDENCE_INCOMPLETE") {
				return fmt.Errorf("attempt %s/%d has neither a log hash nor EVIDENCE_INCOMPLETE", attempt.StageID, attempt.Attempt)
			}
		} else {
			actual, err := e.HashStageLog(detail.Run.ID, attempt.LogPath)
			if err != nil {
				return fmt.Errorf("verify attempt %s/%d log: %w", attempt.StageID, attempt.Attempt, err)
			}
			if actual != attempt.SHA256 {
				return fmt.Errorf("attempt %s/%d log hash does not match durable state", attempt.StageID, attempt.Attempt)
			}
		}
		if current, exists := latest[attempt.StageIndex]; !exists || attempt.Attempt > current.Attempt {
			latest[attempt.StageIndex] = attempt
		}
	}
	for _, stage := range detail.Stages {
		// Resume keeps the historical attempt count but clears LogPath until a
		// new attempt actually starts. In that state the stage is not a mirror
		// of the previous attempt; the attempt record itself remains verified.
		if stage.Attempts == 0 || stage.LogPath == "" {
			continue
		}
		attempt, exists := latest[stage.Index]
		if !exists || attempt.Attempt != stage.Attempts {
			return fmt.Errorf("stage %s latest attempt metadata is missing", stage.ID)
		}
		if stage.Status != attempt.Status || stage.LogPath != attempt.LogPath || stage.SHA256 != attempt.SHA256 {
			return fmt.Errorf("stage %s does not mirror its latest attempt evidence", stage.ID)
		}
	}
	return nil
}

func writeReport(directory string, detail RunDetail) (string, error) {
	manifestPath := filepath.Join(directory, "checksums.sha256")
	manifestHash, err := hashFile(manifestPath)
	if err != nil {
		return "", err
	}
	report := renderReport(detail, manifestHash)
	reportPath := filepath.Join(directory, "report.md")
	if err := writeAtomic(reportPath, []byte(report), 0o600); err != nil {
		return "", err
	}
	return reportPath, nil
}

// HashStageLog hashes a runner-created attempt log. The relative path must be
// the canonical path returned by OpenStageLog and must resolve to a regular
// file beneath the run directory.
func (e *EvidenceStore) HashStageLog(runID string, relativeLog string) (string, error) {
	path, err := e.resolveStageLog(runID, relativeLog)
	if err != nil {
		return "", err
	}
	return hashFile(path)
}

// RemoveUntrackedStageLog removes an attempt log that was created by
// OpenStageLog but whose attempt was not committed to the database. Callers
// are responsible for using it only before the corresponding DB insert.
func (e *EvidenceStore) RemoveUntrackedStageLog(runID string, relativeLog string) error {
	path, err := e.resolveStageLog(runID, relativeLog)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("remove untracked stage log: %w", err)
	}
	return nil
}

func (e *EvidenceStore) resolveStageLog(runID string, relativeLog string) (string, error) {
	if !attemptLogPathPattern.MatchString(relativeLog) {
		return "", fmt.Errorf("invalid stage log path %q", relativeLog)
	}
	cleanRelative := filepath.Clean(filepath.FromSlash(relativeLog))
	if filepath.IsAbs(cleanRelative) || filepath.ToSlash(cleanRelative) != relativeLog {
		return "", fmt.Errorf("invalid stage log path %q", relativeLog)
	}
	directory, err := e.runDir(runID)
	if err != nil {
		return "", err
	}
	path := filepath.Join(directory, cleanRelative)
	relative, err := filepath.Rel(directory, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("stage log path escapes run directory")
	}
	info, err := os.Lstat(path)
	if err != nil {
		return "", err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return "", fmt.Errorf("stage log is not a regular file: %s", relativeLog)
	}
	resolvedDirectory, err := filepath.EvalSymlinks(directory)
	if err != nil {
		return "", err
	}
	resolvedPath, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", err
	}
	resolvedRelative, err := filepath.Rel(resolvedDirectory, resolvedPath)
	if err != nil || resolvedRelative == ".." || strings.HasPrefix(resolvedRelative, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("stage log path escapes run directory through a symbolic link")
	}
	return path, nil
}

func (e *EvidenceStore) runDir(runID string) (string, error) {
	if !runIDPattern.MatchString(runID) {
		return "", fmt.Errorf("invalid run id %q", runID)
	}
	return filepath.Join(e.stateDir, "runs", runID), nil
}

type stageLog struct {
	mu        sync.Mutex
	file      *os.File
	remaining int64
	truncated bool
	firstErr  error
}

func (l *stageLog) WriteLine(stream string, line string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.file == nil || l.truncated {
		return nil
	}
	line = redact(line)
	payload := []byte(fmt.Sprintf("%s | %s\n", stream, line))
	if int64(len(payload)) > l.remaining {
		marker := []byte("runner | [LOG_TRUNCATED]\n")
		if int64(len(marker)) <= l.remaining {
			if _, err := l.file.Write(marker); err != nil {
				l.firstErr = err
				l.truncated = true
				return err
			}
		}
		l.truncated = true
		l.remaining = 0
		return nil
	}
	if _, err := l.file.Write(payload); err != nil {
		l.firstErr = err
		l.truncated = true
		return err
	}
	l.remaining -= int64(len(payload))
	return nil
}

func (l *stageLog) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.file == nil {
		return l.firstErr
	}
	if err := l.file.Sync(); err != nil {
		l.file.Close()
		l.file = nil
		if l.firstErr != nil {
			return l.firstErr
		}
		return err
	}
	err := l.file.Close()
	l.file = nil
	if l.firstErr != nil {
		return l.firstErr
	}
	return err
}

func redact(value string) string {
	for _, rule := range redactionPatterns {
		value = rule.pattern.ReplaceAllString(value, rule.replacement)
	}
	return value
}

func scanOutput(reader io.Reader, stream string, log *stageLog) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		_ = log.WriteLine(stream, scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		_ = log.WriteLine("runner", "output read failed")
		_, _ = io.Copy(io.Discard, reader)
	}
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".staging-accept-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(mode); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

func hashFile(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func writeChecksums(runDir string, manifestPath string) error {
	type entry struct {
		path string
		hash string
	}
	entries := make([]entry, 0)
	err := filepath.WalkDir(runDir, func(path string, item os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if !shouldChecksum(path, item, manifestPath) {
			return nil
		}
		relative, err := filepath.Rel(runDir, path)
		if err != nil {
			return err
		}
		digest, err := hashFile(path)
		if err != nil {
			return err
		}
		entries = append(entries, entry{path: filepath.ToSlash(relative), hash: digest})
		return nil
	})
	if err != nil {
		return err
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].path < entries[j].path })
	var output strings.Builder
	for _, item := range entries {
		fmt.Fprintf(&output, "%s  %s\n", item.hash, item.path)
	}
	return writeAtomic(manifestPath, []byte(output.String()), 0o600)
}

func shouldChecksum(path string, item os.DirEntry, manifestPath string) bool {
	if item.IsDir() || !item.Type().IsRegular() {
		return false
	}
	base := filepath.Base(path)
	return base != filepath.Base(manifestPath) &&
		base != "report.md" &&
		!strings.HasPrefix(base, ".staging-accept-")
}

func verifyChecksums(runDir string) error {
	manifest, err := os.Open(filepath.Join(runDir, "checksums.sha256"))
	if err != nil {
		return err
	}
	defer manifest.Close()
	entries := make(map[string]string)
	scanner := bufio.NewScanner(manifest)
	for scanner.Scan() {
		parts := strings.SplitN(scanner.Text(), "  ", 2)
		if len(parts) != 2 || len(parts[0]) != sha256.Size*2 {
			return errors.New("invalid checksum manifest")
		}
		if _, err := hex.DecodeString(parts[0]); err != nil {
			return errors.New("invalid checksum digest")
		}
		manifestPath := parts[1]
		if manifestPath == "" || filepath.ToSlash(filepath.Clean(filepath.FromSlash(manifestPath))) != manifestPath {
			return errors.New("checksum path is not canonical")
		}
		if _, exists := entries[manifestPath]; exists {
			return fmt.Errorf("duplicate checksum path: %s", manifestPath)
		}
		path := filepath.Join(runDir, filepath.FromSlash(manifestPath))
		relative, err := filepath.Rel(runDir, path)
		if err != nil || strings.HasPrefix(relative, ".."+string(filepath.Separator)) || relative == ".." {
			return errors.New("checksum path escapes run directory")
		}
		info, err := os.Lstat(path)
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("checksum path is not a regular file: %s", manifestPath)
		}
		actual, err := hashFile(path)
		if err != nil {
			return err
		}
		if actual != parts[0] {
			return fmt.Errorf("checksum mismatch: %s", manifestPath)
		}
		entries[manifestPath] = parts[0]
	}
	if err := scanner.Err(); err != nil {
		return err
	}
	for _, required := range []string{"plan.json", "events.ndjson", "summary.json"} {
		if _, exists := entries[required]; !exists {
			return fmt.Errorf("checksum manifest is missing required file: %s", required)
		}
	}
	expected := make(map[string]struct{})
	manifestPath := filepath.Join(runDir, "checksums.sha256")
	if err := filepath.WalkDir(runDir, func(path string, item os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if !shouldChecksum(path, item, manifestPath) {
			return nil
		}
		relative, err := filepath.Rel(runDir, path)
		if err != nil {
			return err
		}
		expected[filepath.ToSlash(relative)] = struct{}{}
		return nil
	}); err != nil {
		return err
	}
	for path := range expected {
		if _, exists := entries[path]; !exists {
			return fmt.Errorf("checksum manifest does not cover evidence file: %s", path)
		}
	}
	for path := range entries {
		if _, exists := expected[path]; !exists {
			return fmt.Errorf("checksum manifest contains unexpected file: %s", path)
		}
	}
	return nil
}

func renderReport(detail RunDetail, manifestHash string) string {
	var report strings.Builder
	report.WriteString("# Staging acceptance report\n\n")
	fmt.Fprintf(&report, "- Run: `%s`\n", detail.Run.ID)
	fmt.Fprintf(&report, "- Outcome: `%s`\n", detail.Run.Status)
	fmt.Fprintf(&report, "- Environment: `%s`\n", detail.Run.Plan.Environment)
	fmt.Fprintf(&report, "- Profile: `%s`\n", detail.Run.Plan.Profile)
	if detail.Run.FailureReason != "" {
		fmt.Fprintf(&report, "- Reason: `%s`\n", detail.Run.FailureReason)
	}
	fmt.Fprintf(&report, "- Evidence manifest: `checksums.sha256` (`%s`)\n", manifestHash)
	report.WriteString("\n## Declared candidate builds\n\n")
	fmt.Fprintf(&report, "- Backend: `%s`\n", detail.Run.Plan.Builds.Backend)
	fmt.Fprintf(&report, "- Frontend: `%s`\n", detail.Run.Plan.Builds.Frontend)
	fmt.Fprintf(&report, "- Web protocol: `%s`\n", detail.Run.Plan.Builds.WebProtocol)
	fmt.Fprintf(&report, "- Android protocol: `%s`\n", detail.Run.Plan.Builds.AndroidProtocol)
	report.WriteString("\n## Stages\n\n")
	report.WriteString("| Stage | Status | Attempts | Exit | Reason |\n")
	report.WriteString("|---|---:|---:|---:|---|\n")
	for _, stage := range detail.Stages {
		exit := "-"
		if stage.ExitCode != nil {
			exit = fmt.Sprint(*stage.ExitCode)
		}
		reason := stage.Reason
		if reason == "" {
			reason = "-"
		}
		fmt.Fprintf(&report, "| `%s` | `%s` | %d | %s | `%s` |\n", stage.ID, stage.Status, stage.Attempts, exit, reason)
	}
	if len(detail.Attempts) > 0 {
		report.WriteString("\nAttempt evidence metadata is recorded in `summary.json`; missing recovery logs are marked `EVIDENCE_INCOMPLETE`.\n")
	}
	return report.String()
}
