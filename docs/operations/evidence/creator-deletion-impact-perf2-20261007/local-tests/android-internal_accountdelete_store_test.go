package accountdelete

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/pkg/errors"
	"github.com/redis/go-redis/v9"
)

const testPhone = "account-delete-test-fixture"

func testStore(t *testing.T) (*Store, *miniredis.Miniredis) {
	t.Helper()
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr(), MaxRetries: -1})
	t.Cleanup(func() { _ = client.Close() })
	return NewStore(client, "test:"), server
}

func TestConcurrentClaimGrantsExactlyOneOperation(t *testing.T) {
	store, _ := testStore(t)
	ctx := context.Background()
	const count = 32
	type result struct {
		record  *Record
		claimed bool
		err     error
	}
	results := make(chan result, count)
	start := make(chan struct{})
	var wait sync.WaitGroup
	for i := range count {
		wait.Add(1)
		go func(i int) {
			defer wait.Done()
			<-start
			record, claimed, err := store.Claim(ctx, testPhone, fmt.Sprintf("op-%d", i))
			results <- result{record, claimed, err}
		}(i)
	}
	close(start)
	wait.Wait()
	close(results)
	claims := 0
	var operationID string
	for result := range results {
		if result.err != nil || result.record == nil {
			t.Fatalf("认领失败：record=%v err=%v", result.record, result.err)
		}
		if result.claimed {
			claims++
		}
		if operationID == "" {
			operationID = result.record.OperationID
		}
		if result.record.OperationID != operationID {
			t.Fatalf("并发认领返回不同操作：%q 与 %q", operationID, result.record.OperationID)
		}
	}
	if claims != 1 {
		t.Fatalf("认领成功次数=%d，期望 1", claims)
	}
	for _, retryID := range []string{operationID, "different-operation"} {
		record, claimed, err := store.Claim(ctx, testPhone, retryID)
		if err != nil || claimed || record.OperationID != operationID {
			t.Fatalf("重试没有复用原操作：record=%v claimed=%v err=%v", record, claimed, err)
		}
	}
}

func TestRecordPersistsAcrossStoreRecreationWithoutTTL(t *testing.T) {
	store, server := testStore(t)
	ctx := context.Background()
	record, claimed, err := store.Claim(ctx, testPhone, "op-persistent")
	if err != nil || !claimed {
		t.Fatalf("首次认领失败：claimed=%v err=%v", claimed, err)
	}
	createdAt := record.CreatedAt
	record.State, record.IQID = StatePending, "iq-delete-1"
	record.ResponseType, record.ErrorCode, record.Reason = "error", "500", "response unconfirmed"
	if err := store.Save(ctx, testPhone, record); err != nil {
		t.Fatal(err)
	}
	if ttl := server.TTL(store.key(testPhone)); ttl != 0 {
		t.Fatalf("注销记录不应设置 TTL，实际 %v", ttl)
	}
	server.FastForward(365 * 24 * time.Hour)
	newClient := redis.NewClient(&redis.Options{Addr: server.Addr(), MaxRetries: -1})
	t.Cleanup(func() { _ = newClient.Close() })
	reopened := NewStore(newClient, "test:")
	loaded, err := reopened.Get(ctx, testPhone)
	if err != nil || loaded == nil {
		t.Fatalf("重新创建存储后未读到记录：record=%v err=%v", loaded, err)
	}
	if loaded.OperationID != record.OperationID || loaded.State != StatePending ||
		loaded.IQID != record.IQID || loaded.ErrorCode != "500" || loaded.CreatedAt != createdAt {
		t.Fatalf("持久化内容变化：%+v", loaded)
	}
	blocked, err := reopened.Blocked(ctx, testPhone)
	if err != nil || !blocked {
		t.Fatalf("重启后的 PENDING 必须阻断：blocked=%v err=%v", blocked, err)
	}
	otherNamespace, err := NewStore(newClient, "other:").Get(ctx, testPhone)
	if err != nil || otherNamespace != nil {
		t.Fatalf("记录跨命名空间泄漏：record=%v err=%v", otherNamespace, err)
	}
	for _, key := range server.Keys() {
		if strings.Contains(key, testPhone) {
			t.Fatal("Redis 键含明文账号")
		}
	}
}

func TestSaveCannotReplaceDifferentOperationOrCreateUnclaimedRecord(t *testing.T) {
	store, _ := testStore(t)
	ctx := context.Background()
	record, _, err := store.Claim(ctx, testPhone, "original")
	if err != nil {
		t.Fatal(err)
	}
	wrong := *record
	wrong.OperationID, wrong.State = "replacement", StateRejected
	if err := store.Save(ctx, testPhone, &wrong); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("覆盖其它操作应冲突：%v", err)
	}
	loaded, err := store.Get(ctx, testPhone)
	if err != nil || loaded.OperationID != "original" || loaded.State != StateStarted {
		t.Fatalf("错误操作覆盖原记录：record=%v err=%v", loaded, err)
	}
	wrong = *record
	wrong.AccountHash = Hash("unclaimed")
	if err := store.Save(ctx, "unclaimed", &wrong); !errors.Is(err, ErrRecordNotFound) {
		t.Fatalf("未认领记录应拒绝创建：%v", err)
	}
	wrong = *record
	wrong.AccountHash = Hash("another-account")
	if err := store.Save(ctx, testPhone, &wrong); !errors.Is(err, ErrInvalidRecord) {
		t.Fatalf("账号身份不匹配应拒绝：%v", err)
	}
}

func TestBlockedAllowsOnlyAbsentRejectedAndNotSent(t *testing.T) {
	store, _ := testStore(t)
	ctx := context.Background()
	if blocked, err := store.Blocked(ctx, testPhone); err != nil || blocked {
		t.Fatalf("无记录应放行：blocked=%v err=%v", blocked, err)
	}
	record, _, err := store.Claim(ctx, testPhone, "op-states")
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range []struct {
		state   string
		blocked bool
	}{
		{StateStarted, true}, {StatePending, true}, {"CONFIRMED", true},
		{"UNKNOWN", true}, {"UNRECOGNIZED_STATE", true},
		{StateRejected, false}, {StateNotSent, false},
	} {
		t.Run(item.state, func(t *testing.T) {
			record.State = item.state
			if err := store.Save(ctx, testPhone, record); err != nil {
				t.Fatal(err)
			}
			blocked, err := store.Blocked(ctx, testPhone)
			if err != nil || blocked != item.blocked {
				t.Fatalf("状态 %s 的阻断结果=%v err=%v", item.state, blocked, err)
			}
		})
	}
}

func TestRedisErrorsAndCorruptRecordsFailClosed(t *testing.T) {
	store, server := testStore(t)
	ctx := context.Background()
	record, _, err := store.Claim(ctx, testPhone, "op-errors")
	if err != nil {
		t.Fatal(err)
	}
	for _, payload := range []string{"not-json", `{}`, `{"operationId":"op-errors","state":"REJECTED"}`} {
		if err := server.Set(store.key(testPhone), payload); err != nil {
			t.Fatal(err)
		}
		if blocked, err := store.Blocked(ctx, testPhone); err == nil || !blocked {
			t.Fatalf("损坏记录应阻断：blocked=%v err=%v", blocked, err)
		}
		if _, claimed, err := store.Claim(ctx, testPhone, "retry"); err == nil || claimed {
			t.Fatalf("损坏记录不能重新认领：claimed=%v err=%v", claimed, err)
		}
	}
	server.SetError("ERR unavailable")
	if blocked, err := store.Blocked(ctx, testPhone); err == nil || !blocked {
		t.Fatalf("Redis 错误应阻断：blocked=%v err=%v", blocked, err)
	}
	if _, claimed, err := store.Claim(ctx, testPhone, "retry"); err == nil || claimed {
		t.Fatalf("Redis 错误不能认领：claimed=%v err=%v", claimed, err)
	}
	if err := store.Save(ctx, testPhone, record); err == nil {
		t.Fatal("Redis 错误不能保存成功")
	}
}

func TestNilRedisIsCompatibleOnlyWhenDeletionIsDisabled(t *testing.T) {
	store := NewStore(nil, "test:")
	ctx := context.Background()
	t.Setenv("WA_ACCOUNT_DELETE_TOKEN", "")
	if blocked, err := store.Blocked(ctx, testPhone); err != nil || blocked {
		t.Fatalf("未启用注销且无 Redis 的旧测试应兼容：blocked=%v err=%v", blocked, err)
	}
	if _, _, err := store.Claim(ctx, testPhone, "op"); !errors.Is(err, ErrStoreUnavailable) {
		t.Fatalf("无 Redis 始终不能认领：%v", err)
	}
	t.Setenv("WA_ACCOUNT_DELETE_TOKEN", "test-feature-enabled")
	if blocked, err := store.Blocked(ctx, testPhone); !errors.Is(err, ErrStoreUnavailable) || !blocked {
		t.Fatalf("启用注销后缺少 Redis 应阻断：blocked=%v err=%v", blocked, err)
	}
}

func TestRecordJSONUsesCamelCaseAndHashIsStable(t *testing.T) {
	if Hash(" test ") != Hash("test") || len(Hash("test")) != 64 {
		t.Fatal("账号哈希不稳定")
	}
	payload, err := json.Marshal(Record{})
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err := json.Unmarshal(payload, &fields); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"operationId", "accountHash", "state", "iqId", "responseType", "errorCode", "reason", "createdAt", "updatedAt"} {
		if _, ok := fields[key]; !ok {
			t.Fatalf("缺少 JSON 字段 %s", key)
		}
	}
}

func TestAuditTaskNotSentRejectedPersistAndRejectNewGeneration(t *testing.T) {
 for _, state := range []string{StateNotSent, StateRejected} { t.Run(state,func(t *testing.T){
  s, server := testStore(t); ctx:=context.Background(); binding:=testBinding(testPhone)
  r,claimed,err:=s.ClaimTask(ctx,testPhone,binding.OperationID,binding);if err!=nil||!claimed {t.Fatal(err)}
  r.State=state;if err:=s.Save(ctx,testPhone,r);err!=nil {t.Fatal(err)}
  server.FastForward(365*24*time.Hour)
  reopened:=NewStore(s.client,s.prefix)
  blocked,err:=reopened.Blocked(ctx,testPhone);if err!=nil||!blocked {t.Fatalf("task %s unexpectedly released",state)}
  newer:=*binding;newer.AccountID="999";newer.OperationID="delete-new-generation-001"
  old,claimed,err:=reopened.ClaimTask(ctx,testPhone,newer.OperationID,&newer)
  if err!=nil||claimed||old.OperationID!=binding.OperationID {t.Fatal("new generation not bound to original tombstone")}
  if blocked,err:=reopened.Blocked(ctx,"unrelated-account");err!=nil||blocked {t.Fatal("unrelated account blocked")}
 })}
}
