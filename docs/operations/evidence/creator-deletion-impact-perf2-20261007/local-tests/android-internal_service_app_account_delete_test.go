package app

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/pkg/errors"
	"go.uber.org/zap"

	"ws-go/internal/accountdelete"
	"ws-go/internal/configs"
	"ws-go/internal/db"
	"ws-go/internal/service/define"
	"ws-go/internal/service/jabber"
	"ws-go/internal/service/node"
	"ws-go/internal/service/node/nodes"
	"ws-go/internal/service/wacontext"
	"ws-go/internal/service/waproto"
	"ws-go/pkg/logger"
)

func newAccountDeletionTestApp() *WaApp {
	jid := jabber.NewJID("19990000001")
	w := newLifecycleTestWaApp(define.Online, 1)
	w.AccountInfo = NewAccountInfo(jid)
	w.SetCliPayload(&waproto.ClientPayload{UserAgent: &waproto.ClientPayload_UserAgent{Platform: waproto.ClientPayload_UserAgent_ANDROID.Enum()}})
	w.wactx = wacontext.NewContext(jid, logger.NewContainer(zap.NewNop()), context.Background())
	w.netWork = connectedTestNetwork{}
	w.node = &node.MainNodeProcessor{}
	return w
}

func TestAccountDeletionRequiresMatchingOnlinePrimaryIdentity(t *testing.T) {
	tests := []struct {
		name   string
		change func(*WaApp)
		phone  string
	}{
		{"ios", func(w *WaApp) { w.clientPayload.UserAgent.Platform = waproto.ClientPayload_UserAgent_IOS.Enum() }, "19990000001"},
		{"ios business", func(w *WaApp) {
			w.clientPayload.UserAgent.Platform = waproto.ClientPayload_UserAgent_PLATFORM_11.Enum()
		}, "19990000001"},
		{"missing platform", func(w *WaApp) { w.clientPayload.UserAgent.Platform = nil }, "19990000001"},
		{"wrong account", func(*WaApp) {}, "19990000002"},
		{"companion", func(w *WaApp) { j := w.DeviceJid(); j.Device = 1; w.SetDeviceJid(j) }, "19990000001"},
		{"offline", func(w *WaApp) { w.loginStatus.Store(define.Offline) }, "19990000001"},
		{"stopping", func(w *WaApp) { w.IsExpectedLogout.Store(true) }, "19990000001"},
		{"identity mismatch", func(w *WaApp) {
			w.wactx = wacontext.NewContext(jabber.NewJID("19990000002"), logger.NewContainer(zap.NewNop()), context.Background())
		}, "19990000001"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := newAccountDeletionTestApp()
			tt.change(w)
			if err := w.BeginAccountDeletion(tt.phone); err == nil || w.AccountDeletionBlocked() {
				t.Fatalf("invalid primary identity accepted: err=%v blocked=%v", err, w.AccountDeletionBlocked())
			}
		})
	}
}

func TestAccountDeletionOutcomesRetainUnknownAndAcceptedState(t *testing.T) {
	for _, outcome := range []string{nodes.AccountDeleteAccepted, nodes.AccountDeleteRejected, nodes.AccountDeleteUnknown, nodes.AccountDeleteNotSent} {
		t.Run(outcome, func(t *testing.T) {
			w := newAccountDeletionTestApp()
			if err := w.BeginAccountDeletion(w.UserName()); err != nil {
				t.Fatal(err)
			}
			if w.LoginStatus() != define.Online || !w.netWork.Connected() {
				t.Fatal("begin stopped the authenticated socket")
			}
			result, err := w.sendAccountDeletion(context.Background(), func(context.Context) (nodes.AccountDeleteIQResult, error) {
				if w.loginAttemptAllowed("") || w.BeginReconnectAttempt("retry", "test") {
					t.Fatal("reconnect allowed during deletion")
				}
				return nodes.AccountDeleteIQResult{IQID: "101", Outcome: outcome}, nil
			})
			if err != nil || result.Outcome != outcome {
				t.Fatalf("result=%v err=%v", result, err)
			}
			if !w.AccountDeletionBlocked() || !w.IsExpectedLogout.Load() {
				t.Fatal("result released gate before persistence")
			}
			if w.LoginStatus() != define.Online {
				t.Fatal("response cleaned state before caller persisted evidence")
			}
			if err := w.ReleaseAccountDeletionAfterRejection(); err == nil {
				t.Fatal("unpersisted rejection released gate")
			}
			if outcome == nodes.AccountDeleteRejected || outcome == nodes.AccountDeleteNotSent {
				if err := w.releaseAccountDeletionWithRecord(&accountdelete.Record{IQID: "wrong", State: outcome}); err == nil {
					t.Fatal("wrong persisted IQ released gate")
				}
				if err := w.releaseAccountDeletionWithRecord(&accountdelete.Record{IQID: "101", State: outcome}); err != nil {
					t.Fatal(err)
				}
				if w.AccountDeletionBlocked() || w.IsExpectedLogout.Load() {
					t.Fatal("persisted rejection did not restore session")
				}
			}

			if err := w.CompleteAccountDeletion(); outcome != nodes.AccountDeleteAccepted && err == nil {
				t.Fatal("cleanup allowed without accepted response")
			}
		})
	}
}

func TestAccountDeletionOnlyOneSenderCanRun(t *testing.T) {
	w := newAccountDeletionTestApp()
	if err := w.BeginAccountDeletion(w.UserName()); err != nil {
		t.Fatal(err)
	}
	var writes atomic.Int32
	var wg sync.WaitGroup
	for range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = w.sendAccountDeletion(context.Background(), func(context.Context) (nodes.AccountDeleteIQResult, error) {
				writes.Add(1)
				return nodes.AccountDeleteIQResult{Outcome: nodes.AccountDeleteUnknown}, nil
			})
		}()
	}
	wg.Wait()
	if writes.Load() != 1 {
		t.Fatalf("destructive writes=%d want 1", writes.Load())
	}
	if err := w.releaseAccountDeletionWithRecord(&accountdelete.Record{IQID: "101", State: nodes.AccountDeleteNotSent}); err == nil {
		t.Fatal("unknown deletion was cancellable")
	}
}

func TestAccountDeletionUnknownExitPreservesCredentialsAndCache(t *testing.T) {
	w := newAccountDeletionTestApp()
	if err := w.BeginAccountDeletion(w.UserName()); err != nil {
		t.Fatal(err)
	}
	w.finishAccountDeletion(nodes.AccountDeleteIQResult{Outcome: nodes.AccountDeleteUnknown})
	appCache.Store(w.UserName(), w)
	t.Cleanup(func() { appCache.Delete(w.UserName()) })
	w.loginStatus.Store(define.AuthFailed)
	// axolotlManager 故意为空：任何既有凭据清理路径都会暴露为 panic。
	w.Exit()
	RemoveWSApp(w.UserName())
	if GetWSApp(w.UserName()) != w || GetValidWSApp(w.UserName()) != nil {
		t.Fatal("unknown state lost cache protection")
	}
	if !w.AccountDeletionBlocked() || !w.IsExpectedLogout.Load() {
		t.Fatal("unknown state released reconnect gate")
	}
	if err := SetWSApp(w.UserName(), newAccountDeletionTestApp()); !errors.Is(err, ErrAccountDeletionBlocked) {
		t.Fatalf("replacement allowed: %v", err)
	}
}

func TestAccountDeletionRejectedDoesNotUndoConcurrentStop(t *testing.T) {
	w := newAccountDeletionTestApp()
	if err := w.BeginAccountDeletion(w.UserName()); err != nil {
		t.Fatal(err)
	}
	w.StopNetwork()
	w.finishAccountDeletion(nodes.AccountDeleteIQResult{IQID: "102", Outcome: nodes.AccountDeleteRejected})
	if err := w.releaseAccountDeletionWithRecord(&accountdelete.Record{IQID: "102", State: nodes.AccountDeleteRejected}); err != nil {
		t.Fatal(err)
	}
	if !w.IsExpectedLogout.Load() {
		t.Fatal("rejected deletion undid explicit stop")
	}
}

func TestAccountDeletionAcceptedCannotCleanBeforePersistence(t *testing.T) {
	w := newAccountDeletionTestApp()
	if err := w.BeginAccountDeletion(w.UserName()); err != nil {
		t.Fatal(err)
	}
	w.finishAccountDeletion(nodes.AccountDeleteIQResult{IQID: "999", Outcome: nodes.AccountDeleteAccepted})
	if err := w.CompleteAccountDeletion(); err == nil {
		t.Fatal("cleanup accepted without durable matching IQ result")
	}
	if w.accountDeleteNetworkStopped.Load() || !w.AccountDeletionBlocked() || w.LoginStatus() != define.Online {
		t.Fatal("cleanup started before durable evidence")
	}
}

func TestAccountDeletionAllowsAndroidBusinessPrimary(t *testing.T) {
	w := newAccountDeletionTestApp()
	w.clientPayload.UserAgent.Platform = waproto.ClientPayload_UserAgent_PLATFORM_10.Enum()
	if err := w.BeginAccountDeletion(w.UserName()); err != nil {
		t.Fatal(err)
	}
}

func TestTaskDeletionQuarantineRequiresDurableMatchingIdentity(t *testing.T) {
	for _, tc := range []struct {
		name       string
		claim      string
		change     func(*WaApp)
		wantFrozen bool
	}{
		{"offline task", "task", func(w *WaApp) { w.loginStatus.Store(define.Offline) }, true},
		{"companion task", "task", func(w *WaApp) { j := w.DeviceJid(); j.Device = 1; w.SetDeviceJid(j) }, true},
		{"wrong platform task", "task", func(w *WaApp) { w.clientPayload.UserAgent.Platform = waproto.ClientPayload_UserAgent_IOS.Enum() }, true},
		{"missing claim", "", func(*WaApp) {}, false},
		{"legacy claim", "legacy", func(*WaApp) {}, false},
		{"wrong device identity", "task", func(w *WaApp) { w.SetDeviceJid(jabber.NewJID("19990000002")) }, false},
		{"wrong session identity", "task", func(w *WaApp) { w.wactx.Update(jabber.NewJID("19990000002"), zap.NewNop()) }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := miniredis.RunT(t)
			if err := db.InitRedisWithConfig(configs.RedisConfig{Addr: server.Addr(), KeyPrefix: "task-quarantine-test:"}); err != nil {
				t.Fatal(err)
			}
			client := db.GetRedis()
			t.Cleanup(func() { _ = client.Close() })
			w := newAccountDeletionTestApp()
			phone := w.UserName()
			appCache.Store(phone, w)
			t.Cleanup(func() { appCache.Delete(phone) })
			var binding *accountdelete.TaskBinding
			var record *accountdelete.Record
			if tc.claim == "task" {
				binding = &accountdelete.TaskBinding{TenantID: "1", TaskID: "2", ExecutionID: "3", AccountID: "4", AccountHash: accountdelete.Hash(phone), CreateOperationID: "create-0001", OperationID: "task-quarantine-operation"}
			}
			if tc.claim != "" {
				var err error
				record, _, err = accountdelete.ClaimTask(context.Background(), phone, "task-quarantine-operation", binding)
				if err != nil {
					t.Fatal(err)
				}
			}
			tc.change(w)
			// Claim 成功后的存储故障也不能重新放行业务。
			server.Close()
			err := w.QuarantineTaskAccountDeletion(phone, record)
			if (err == nil) != tc.wantFrozen || w.AccountDeletionBlocked() != tc.wantFrozen {
				t.Fatalf("err=%v blocked=%v", err, w.AccountDeletionBlocked())
			}
			if tc.wantFrozen {
				if GetValidWSApp(phone) != nil || !w.IsExpectedLogout.Load() || w.loginAttemptAllowed("") || w.BeginReconnectAttempt("retry", "test") {
					t.Fatal("quarantined task account remained usable")
				}
				if !w.netWork.Connected() || w.accountDeleteNetworkStopped.Load() || w.accountDeleteState.Load() != accountDeleteNotSent {
					t.Fatal("quarantine stopped socket or changed deletion outcome")
				}
				var sends atomic.Int32
				_, _ = w.sendAccountDeletion(context.Background(), func(context.Context) (nodes.AccountDeleteIQResult, error) {
					sends.Add(1)
					return nodes.AccountDeleteIQResult{}, nil
				})
				if sends.Load() != 0 {
					t.Fatal("quarantine permitted deletion IQ")
				}
			}
		})
	}
}

func TestTaskDeletionRejectionCannotReleaseGate(t *testing.T) {
	w := newAccountDeletionTestApp()
	if err := w.BeginAccountDeletion(w.UserName()); err != nil {
		t.Fatal(err)
	}
	w.finishAccountDeletion(nodes.AccountDeleteIQResult{IQID: "task-iq", Outcome: nodes.AccountDeleteRejected})
	if err := w.releaseAccountDeletionWithRecord(&accountdelete.Record{State: nodes.AccountDeleteRejected, IQID: "task-iq", Binding: &accountdelete.TaskBinding{}}); err == nil || !w.AccountDeletionBlocked() {
		t.Fatal("task rejection released one-time account")
	}
}

func TestTaskDeletionQuarantineFreezesMatchingReplacedInstance(t *testing.T) {
	original := newAccountDeletionTestApp()
	current := newAccountDeletionTestApp()
	phone := original.UserName()
	appCache.Store(phone, current)
	t.Cleanup(func() { appCache.Delete(phone) })
	binding := &accountdelete.TaskBinding{AccountHash: accountdelete.Hash(phone), OperationID: "task-quarantine-operation"}
	record := &accountdelete.Record{AccountHash: binding.AccountHash, OperationID: binding.OperationID, Binding: binding, State: "NOT_SENT"}
	if err := original.QuarantineTaskAccountDeletion(phone, record); err != nil {
		t.Fatal(err)
	}
	if !original.AccountDeletionBlocked() || !current.AccountDeletionBlocked() || GetValidWSApp(phone) != nil {
		t.Fatal("replacement retained a business-capable instance")
	}
}

type accountDeleteStopNetwork struct {
	connectedTestNetwork
	stops atomic.Int32
}

func (n *accountDeleteStopNetwork) Close() { n.stops.Add(1) }

func TestPersistedDeletionRejectsTemporaryIDBindingWithoutReplacingOldInstance(t *testing.T) {
	for _, bind := range []string{"cache", "qr_username"} {
		t.Run(bind, func(t *testing.T) {
			server := miniredis.RunT(t)
			if err := db.InitRedisWithConfig(configs.RedisConfig{Addr: server.Addr(), KeyPrefix: "account-delete-bind-test:"}); err != nil {
				t.Fatal(err)
			}
			client := db.GetRedis()
			t.Cleanup(func() { _ = client.Close() })
			old := newAccountDeletionTestApp()
			originalNetwork := &accountDeleteStopNetwork{}
			old.netWork = originalNetwork
			appCache.Store(old.UserName(), old)
			t.Cleanup(func() { appCache.Delete(old.UserName()) })
			record, _, err := accountdelete.Claim(context.Background(), old.UserName(), "binding-blocked-operation")
			if err != nil {
				t.Fatal(err)
			}
			record.State = nodes.AccountDeleteUnknown
			if err := accountdelete.Save(context.Background(), old.UserName(), record); err != nil {
				t.Fatal(err)
			}
			incoming := newAccountDeletionTestApp()
			temporary := jabber.NewJID("temporary-pairing")
			incoming.SetDeviceJid(temporary)
			incoming.wactx.Update(temporary, zap.NewNop())
			incomingNetwork := &accountDeleteStopNetwork{}
			incoming.netWork = incomingNetwork
			if bind == "cache" {
				err = SetWSApp(old.UserName(), incoming)
			} else {
				err = incoming.UpdateUsername(old.DeviceJid())
			}
			if !errors.Is(err, ErrAccountDeletionBlocked) {
				t.Fatalf("persisted delete bypass accepted: %v", err)
			}
			if GetWSApp(old.UserName()) != old || originalNetwork.stops.Load() != 0 {
				t.Fatal("blocked binding replaced or stopped original instance")
			}
			if !incoming.AccountDeletionBlocked() || incomingNetwork.stops.Load() != 1 || incoming.LoginStatus() != define.Offline {
				t.Fatal("rejected new instance remained usable")
			}
			if incoming.UserName() != temporary.User {
				t.Fatal("rejected QR binding modified username before validation")
			}
		})
	}
}

func TestAuditAccountDeletionHidesConnectedInstanceFromBusinessLookup(t *testing.T) {
 for _,outcome:=range []string{nodes.AccountDeleteUnknown,nodes.AccountDeleteNotSent,nodes.AccountDeleteRejected}{t.Run(outcome,func(t *testing.T){
  w:=newAccountDeletionTestApp();phone:=w.UserName();appCache.Store(phone,w);defer appCache.Delete(phone)
  if err:=w.BeginAccountDeletion(phone);err!=nil{t.Fatal(err)}
  w.finishAccountDeletion(nodes.AccountDeleteIQResult{IQID:"audit",Outcome:outcome})
  if GetValidWSApp(phone)!=nil||GetWSApp(phone)!=w||w.LoginStatus()!=define.Online||!w.netWork.Connected(){t.Fatal("expected blocked lookup with online active instance")}
  RemoveWSApp(phone);if GetWSApp(phone)!=w||w.accountDeleteNetworkStopped.Load(){t.Fatal("remove unexpectedly cleaned frozen account")}
 })}
}

func TestAuditAcceptedCleanupCannotResumeFromPersistedRecordAlone(t *testing.T) {
 server:=miniredis.RunT(t)
 if err:=db.InitRedisWithConfig(configs.RedisConfig{Addr:server.Addr(),KeyPrefix:"audit-cleanup:"});err!=nil{t.Fatal(err)}
 client:=db.GetRedis();t.Cleanup(func(){_=client.Close()})
 fresh:=newAccountDeletionTestApp();phone:=fresh.UserName()
 record,claimed,err:=accountdelete.Claim(context.Background(),phone,"audit-accepted-cleanup");if err!=nil||!claimed{t.Fatal(err)}
 record.State=nodes.AccountDeleteAccepted;record.IQID="audit-accepted-iq"
 if err:=accountdelete.Save(context.Background(),phone,record);err!=nil{t.Fatal(err)}
 if err:=fresh.CompleteAccountDeletion();err==nil||err.Error()!="account deletion has no accepted result to complete"{t.Fatalf("fresh cleanup err=%v",err)}
 if _,err:=CreateWSApp(fresh.AccountInfo);!errors.Is(err,ErrAccountDeletionBlocked){t.Fatalf("rebuild err=%v",err)}
}
