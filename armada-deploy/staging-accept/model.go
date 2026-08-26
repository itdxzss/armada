package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const (
	planSchemaVersion      = 1
	safetyReadOnly         = "read-only"
	safetyControlledCanary = "controlled-canary"
)

type RunStatus string

const (
	RunQueued    RunStatus = "QUEUED"
	RunRunning   RunStatus = "RUNNING"
	RunPassed    RunStatus = "PASS"
	RunFailed    RunStatus = "FAIL"
	RunCancelled RunStatus = "CANCELLED"
)

type StageStatus string

const (
	StageQueued      StageStatus = "QUEUED"
	StageRunning     StageStatus = "RUNNING"
	StagePassed      StageStatus = "PASS"
	StageFailed      StageStatus = "FAIL"
	StageTimedOut    StageStatus = "TIMED_OUT"
	StageCancelled   StageStatus = "CANCELLED"
	StageInterrupted StageStatus = "INTERRUPTED"
)

type Plan struct {
	SchemaVersion     int           `json:"schemaVersion"`
	Profile           string        `json:"profile"`
	Environment       string        `json:"environment"`
	Safety            string        `json:"safety"`
	SafetyEnvelopeRef string        `json:"safetyEnvelopeRef,omitempty"`
	Builds            BuildManifest `json:"builds"`
	Stages            []StageSpec   `json:"stages"`
}

type BuildManifest struct {
	Backend         string `json:"backend"`
	Frontend        string `json:"frontend"`
	WebProtocol     string `json:"webProtocol"`
	AndroidProtocol string `json:"androidProtocol"`
}

type StageSpec struct {
	ID               string   `json:"id"`
	Command          []string `json:"command"`
	WorkingDirectory string   `json:"workingDirectory,omitempty"`
	TimeoutSeconds   int      `json:"timeoutSeconds"`
}

type RunRecord struct {
	ID              string     `json:"id"`
	Plan            Plan       `json:"plan"`
	Status          RunStatus  `json:"status"`
	CreatedAt       time.Time  `json:"createdAt"`
	StartedAt       *time.Time `json:"startedAt,omitempty"`
	FinishedAt      *time.Time `json:"finishedAt,omitempty"`
	HeartbeatAt     *time.Time `json:"heartbeatAt,omitempty"`
	CurrentStage    int        `json:"currentStage"`
	CancelRequested bool       `json:"cancelRequested"`
	EvidenceReady   bool       `json:"evidenceReady"`
	FailureReason   string     `json:"failureReason,omitempty"`
}

type StageRecord struct {
	Index      int         `json:"index"`
	ID         string      `json:"id"`
	Status     StageStatus `json:"status"`
	Attempts   int         `json:"attempts"`
	StartedAt  *time.Time  `json:"startedAt,omitempty"`
	FinishedAt *time.Time  `json:"finishedAt,omitempty"`
	ExitCode   *int        `json:"exitCode,omitempty"`
	Reason     string      `json:"reason,omitempty"`
	LogPath    string      `json:"logPath,omitempty"`
	SHA256     string      `json:"sha256,omitempty"`
}

type AttemptRecord struct {
	StageIndex int         `json:"stageIndex"`
	StageID    string      `json:"stageId"`
	Attempt    int         `json:"attempt"`
	Status     StageStatus `json:"status"`
	StartedAt  time.Time   `json:"startedAt"`
	FinishedAt *time.Time  `json:"finishedAt,omitempty"`
	ExitCode   *int        `json:"exitCode,omitempty"`
	Reason     string      `json:"reason,omitempty"`
	LogPath    string      `json:"logPath"`
	SHA256     string      `json:"sha256,omitempty"`
}

type RunDetail struct {
	Run      RunRecord       `json:"run"`
	Stages   []StageRecord   `json:"stages"`
	Attempts []AttemptRecord `json:"attempts"`
}

var (
	safeIDPattern       = regexp.MustCompile(`^[a-z][a-z0-9-]{0,63}$`)
	fullCommitPattern   = regexp.MustCompile(`^[0-9a-fA-F]{40}$`)
	sensitiveArgPattern = regexp.MustCompile(`(?i)^(?:--?)?(?:password|passwd|token|secret|api[-_]?key|authorization)(?:=|$)`)
)

func loadPlan(path string) (Plan, []byte, error) {
	info, err := os.Stat(path)
	if err != nil {
		return Plan{}, nil, fmt.Errorf("stat plan: %w", err)
	}
	if info.Size() > 1024*1024 {
		return Plan{}, nil, errors.New("plan exceeds 1 MiB")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return Plan{}, nil, fmt.Errorf("read plan: %w", err)
	}
	var plan Plan
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&plan); err != nil {
		return Plan{}, nil, fmt.Errorf("decode plan: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return Plan{}, nil, errors.New("plan must contain exactly one JSON object")
	}
	if err := plan.Validate(); err != nil {
		return Plan{}, nil, err
	}
	canonical, err := json.MarshalIndent(plan, "", "  ")
	if err != nil {
		return Plan{}, nil, fmt.Errorf("encode plan: %w", err)
	}
	return plan, append(canonical, '\n'), nil
}

func (p Plan) Validate() error {
	if p.SchemaVersion != planSchemaVersion {
		return fmt.Errorf("plan schemaVersion must be %d", planSchemaVersion)
	}
	if !safeIDPattern.MatchString(p.Profile) {
		return errors.New("plan profile must match [a-z][a-z0-9-]{0,63}")
	}
	if !safeIDPattern.MatchString(p.Environment) {
		return errors.New("plan environment must match [a-z][a-z0-9-]{0,63}")
	}
	switch p.Safety {
	case safetyReadOnly:
		if p.SafetyEnvelopeRef != "" {
			return errors.New("read-only plans must not declare safetyEnvelopeRef")
		}
	case safetyControlledCanary:
		if !safeIDPattern.MatchString(p.SafetyEnvelopeRef) {
			return errors.New("controlled-canary plans require safetyEnvelopeRef matching [a-z][a-z0-9-]{0,63}")
		}
	default:
		return errors.New("plan safety must be read-only or controlled-canary")
	}
	for name, revision := range map[string]string{
		"backend":         p.Builds.Backend,
		"frontend":        p.Builds.Frontend,
		"webProtocol":     p.Builds.WebProtocol,
		"androidProtocol": p.Builds.AndroidProtocol,
	} {
		if !fullCommitPattern.MatchString(revision) {
			return fmt.Errorf("builds.%s must be a full 40-character Git commit", name)
		}
	}
	if len(p.Stages) == 0 || len(p.Stages) > 64 {
		return errors.New("plan stages must contain 1..64 entries")
	}
	seen := make(map[string]struct{}, len(p.Stages))
	for index, stage := range p.Stages {
		if !safeIDPattern.MatchString(stage.ID) {
			return fmt.Errorf("stage %d id must match [a-z][a-z0-9-]{0,63}", index)
		}
		if _, exists := seen[stage.ID]; exists {
			return fmt.Errorf("duplicate stage id %q", stage.ID)
		}
		seen[stage.ID] = struct{}{}
		if len(stage.Command) == 0 || strings.TrimSpace(stage.Command[0]) == "" {
			return fmt.Errorf("stage %q command is required", stage.ID)
		}
		if !filepath.IsAbs(stage.Command[0]) {
			return fmt.Errorf("stage %q command executable must be absolute", stage.ID)
		}
		for _, argument := range stage.Command {
			if strings.ContainsRune(argument, '\x00') {
				return fmt.Errorf("stage %q command contains NUL", stage.ID)
			}
			if sensitiveArgPattern.MatchString(strings.TrimSpace(argument)) {
				return fmt.Errorf("stage %q command contains a secret-like argument; inject secrets through the service environment", stage.ID)
			}
		}
		if stage.WorkingDirectory != "" && !filepath.IsAbs(stage.WorkingDirectory) {
			return fmt.Errorf("stage %q workingDirectory must be absolute", stage.ID)
		}
		if stage.WorkingDirectory != "" {
			info, err := os.Stat(stage.WorkingDirectory)
			if err != nil || !info.IsDir() {
				return fmt.Errorf("stage %q workingDirectory must be an existing directory", stage.ID)
			}
		}
		if stage.TimeoutSeconds < 1 || stage.TimeoutSeconds > 24*60*60 {
			return fmt.Errorf("stage %q timeoutSeconds must be 1..86400", stage.ID)
		}
	}
	return nil
}

func isTerminalRun(status RunStatus) bool {
	return status == RunPassed || status == RunFailed || status == RunCancelled
}
