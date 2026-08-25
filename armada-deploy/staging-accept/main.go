package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"text/tabwriter"
	"time"
)

func main() {
	os.Exit(runMain(os.Args[1:], os.Stdout, os.Stderr))
}

func runMain(args []string, stdout io.Writer, stderr io.Writer) int {
	if len(args) == 0 {
		printUsage(stderr)
		return 2
	}
	var err error
	switch args[0] {
	case "run":
		err = runCommand(args[1:], stdout, stderr)
	case "serve":
		err = serveCommand(args[1:], stderr)
	case "status":
		err = statusCommand(args[1:], stdout, stderr)
	case "report":
		err = reportCommand(args[1:], stdout, stderr)
	case "cancel":
		err = cancelCommand(args[1:], stdout, stderr)
	case "resume":
		err = resumeCommand(args[1:], stdout, stderr)
	case "help", "--help", "-h":
		printUsage(stdout)
		return 0
	default:
		fmt.Fprintf(stderr, "unknown command %q\n", args[0])
		printUsage(stderr)
		return 2
	}
	if err != nil {
		fmt.Fprintf(stderr, "staging-accept: %v\n", err)
		return 1
	}
	return 0
}

func runCommand(args []string, stdout io.Writer, stderr io.Writer) error {
	flags := flag.NewFlagSet("run", flag.ContinueOnError)
	flags.SetOutput(stderr)
	stateDir := flags.String("state-dir", defaultStateDir(), "runner state directory")
	planPath := flags.String("plan", "", "path to the JSON plan")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *planPath == "" || flags.NArg() != 0 {
		return errors.New("run requires --plan and no positional arguments")
	}
	plan, planJSON, err := loadPlan(*planPath)
	if err != nil {
		return err
	}
	_, store, evidence, err := openRuntime(*stateDir)
	if err != nil {
		return err
	}
	defer store.Close()
	runID, err := newRunID(time.Now())
	if err != nil {
		return err
	}
	if err := evidence.InitRun(runID, planJSON); err != nil {
		return err
	}
	if err := evidence.AppendEvent(eventRecord{
		RunID: runID, Type: "run_queued", Status: string(RunQueued),
	}); err != nil {
		return err
	}
	if err := store.Enqueue(runID, plan, planJSON, time.Now()); err != nil {
		return err
	}
	fmt.Fprintln(stdout, runID)
	return nil
}

func serveCommand(args []string, stderr io.Writer) error {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	flags.SetOutput(stderr)
	stateDir := flags.String("state-dir", defaultStateDir(), "runner state directory")
	once := flags.Bool("once", false, "process queued work and exit")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return errors.New("serve accepts no positional arguments")
	}
	root, store, evidence, err := openRuntime(*stateDir)
	if err != nil {
		return err
	}
	defer store.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return newDaemon(store, evidence, root).Serve(ctx, *once)
}

func statusCommand(args []string, stdout io.Writer, stderr io.Writer) error {
	flags := flag.NewFlagSet("status", flag.ContinueOnError)
	flags.SetOutput(stderr)
	stateDir := flags.String("state-dir", defaultStateDir(), "runner state directory")
	jsonOutput := flags.Bool("json", false, "print JSON")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() > 1 {
		return errors.New("status accepts at most one run id")
	}
	_, store, _, err := openRuntime(*stateDir)
	if err != nil {
		return err
	}
	defer store.Close()
	if flags.NArg() == 1 {
		detail, err := store.Get(flags.Arg(0))
		if err != nil {
			return err
		}
		if *jsonOutput {
			return writeJSON(stdout, detail)
		}
		printDetail(stdout, detail)
		return nil
	}
	runs, err := store.List(20)
	if err != nil {
		return err
	}
	if *jsonOutput {
		return writeJSON(stdout, runs)
	}
	writer := tabwriter.NewWriter(stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, "RUN ID\tSTATUS\tENVIRONMENT\tPROFILE\tCREATED")
	for _, run := range runs {
		fmt.Fprintf(writer, "%s\t%s\t%s\t%s\t%s\n",
			run.ID, run.Status, run.Plan.Environment, run.Plan.Profile, formatTime(run.CreatedAt),
		)
	}
	return writer.Flush()
}

func reportCommand(args []string, stdout io.Writer, stderr io.Writer) error {
	flags := flag.NewFlagSet("report", flag.ContinueOnError)
	flags.SetOutput(stderr)
	stateDir := flags.String("state-dir", defaultStateDir(), "runner state directory")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 1 {
		return errors.New("report requires one run id")
	}
	_, store, evidence, err := openRuntime(*stateDir)
	if err != nil {
		return err
	}
	defer store.Close()
	detail, err := store.Get(flags.Arg(0))
	if err != nil {
		return err
	}
	if !isTerminalRun(detail.Run.Status) {
		return fmt.Errorf("run %q is %s; report is only final for terminal runs", detail.Run.ID, detail.Run.Status)
	}
	if !detail.Run.EvidenceReady {
		return fmt.Errorf("run %q evidence is not ready; let the daemon finish recovery", detail.Run.ID)
	}
	path, err := evidence.VerifiedReport(detail)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	_, err = stdout.Write(data)
	return err
}

func cancelCommand(args []string, stdout io.Writer, stderr io.Writer) error {
	flags := flag.NewFlagSet("cancel", flag.ContinueOnError)
	flags.SetOutput(stderr)
	stateDir := flags.String("state-dir", defaultStateDir(), "runner state directory")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 1 {
		return errors.New("cancel requires one run id")
	}
	_, store, _, err := openRuntime(*stateDir)
	if err != nil {
		return err
	}
	defer store.Close()
	runID := flags.Arg(0)
	before, err := store.Get(runID)
	if err != nil {
		return err
	}
	if isTerminalRun(before.Run.Status) {
		fmt.Fprintln(stdout, before.Run.Status)
		return nil
	}
	status, err := store.RequestCancel(runID, time.Now())
	if err != nil {
		return err
	}
	if status == RunRunning {
		fmt.Fprintln(stdout, "CANCEL_REQUESTED")
	} else {
		fmt.Fprintln(stdout, status)
	}
	return nil
}

func resumeCommand(args []string, stdout io.Writer, stderr io.Writer) error {
	flags := flag.NewFlagSet("resume", flag.ContinueOnError)
	flags.SetOutput(stderr)
	stateDir := flags.String("state-dir", defaultStateDir(), "runner state directory")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 1 {
		return errors.New("resume requires one run id")
	}
	_, store, _, err := openRuntime(*stateDir)
	if err != nil {
		return err
	}
	defer store.Close()
	runID := flags.Arg(0)
	if err := store.Resume(runID); err != nil {
		return err
	}
	fmt.Fprintln(stdout, RunQueued)
	return nil
}

func openRuntime(rawStateDir string) (string, *Store, *EvidenceStore, error) {
	root, err := filepath.Abs(filepath.Clean(rawStateDir))
	if err != nil {
		return "", nil, nil, err
	}
	store, err := openStore(root)
	if err != nil {
		return "", nil, nil, err
	}
	return root, store, newEvidenceStore(root), nil
}

func defaultStateDir() string {
	if configured := os.Getenv("STAGING_ACCEPT_STATE_DIR"); configured != "" {
		return configured
	}
	return ".staging-accept"
}

func newRunID(now time.Time) (string, error) {
	random := make([]byte, 4)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	return now.UTC().Format("20060102T150405Z") + "-" + hex.EncodeToString(random), nil
}

func writeJSON(writer io.Writer, value any) error {
	encoder := json.NewEncoder(writer)
	encoder.SetIndent("", "  ")
	return encoder.Encode(value)
}

func printDetail(writer io.Writer, detail RunDetail) {
	fmt.Fprintf(writer, "Run: %s\nStatus: %s\nEnvironment: %s\nProfile: %s\n",
		detail.Run.ID, detail.Run.Status, detail.Run.Plan.Environment, detail.Run.Plan.Profile,
	)
	if detail.Run.FailureReason != "" {
		fmt.Fprintf(writer, "Reason: %s\n", detail.Run.FailureReason)
	}
	for _, stage := range detail.Stages {
		fmt.Fprintf(writer, "- %s: %s (attempts=%d)\n", stage.ID, stage.Status, stage.Attempts)
	}
}

func printUsage(writer io.Writer) {
	fmt.Fprintln(writer, `staging-accept - durable, single-worker staging acceptance runner

Usage:
  staging-accept run --plan PLAN.json [--state-dir DIR]
  staging-accept serve [--once] [--state-dir DIR]
  staging-accept status [--json] [--state-dir DIR] [RUN_ID]
  staging-accept report [--state-dir DIR] RUN_ID
  staging-accept cancel [--state-dir DIR] RUN_ID
  staging-accept resume [--state-dir DIR] RUN_ID`)
}
