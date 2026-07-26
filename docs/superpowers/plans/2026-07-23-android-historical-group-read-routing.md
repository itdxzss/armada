# Android Historical Group Read Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android Zhuan 操作账号在不改变历史群 baseline 口径和 Web 行为的前提下，完成历史群状态刷新、详情/成员读取和邀请链接读取，并用自动化测试证明两种协议的业务结果等价。

**Architecture:** 历史群业务继续只处理 `baseline` 与实时 `current` 的集合运算；协议防腐层新增固定账号能力的 backend 接口和 Routing Port，按 `ProtocolAccountRef.backend()` 选择 Web 或 Android。Android Zhuan 提供轻量群列表、批量 metadata 摘要和增强后的单群成员响应，Armada 的 Android adapter 使用 `wsPhone` 调用这些原生接口并映射为现有稳定模型；写操作预检继续使用原 Web metadata 端口，避免第一批误开 Android 成员修改。

**Tech Stack:** Java 17、Spring Boot、Spring `RestClient`、JUnit 5、AssertJ、Mockito、Maven；Go 1.25、Gin、标准库 `sync`/`atomic`/`httptest`、Go testing。

---

## Scope and repository map

本计划只实现设计文档 `docs/superpowers/specs/2026-07-23-android-historical-group-staged-routing-design.md` 的第一批。第二批成员管理、第三批 Android 拉手、第四批 Android A 账号营销分别另写计划，不混进本批。

执行前先使用 `superpowers:using-git-worktrees`。Armada 当前工作区存在与本任务无关的营销改动，必须从当前已提交设计的 `ea054f6` 或其后继提交创建隔离 worktree；Android Zhuan 也使用独立 worktree。执行记录先写下技能实际创建并打印出的两个绝对路径；下文“从 Android worktree 根目录”和“从 Armada worktree 根目录”均指这两个已验证目录，不能在当前脏工作区直接实施。

### Android Zhuan file map

- Modify `../whatsapp-server-feature-android-zhuan/api/controller/group.go`: 解析 `includeParticipants`，接收 metadata summaries 请求。
- Create `../whatsapp-server-feature-android-zhuan/api/controller/group_test.go`: 查询参数默认值、显式 `false` 和非法值测试。
- Modify `../whatsapp-server-feature-android-zhuan/api/dto/dto.go`: 增加 metadata summaries 请求 DTO。
- Create `../whatsapp-server-feature-android-zhuan/api/vo/group_metadata.go`: 稳定的 lower-camel summaries 响应类型。
- Modify `../whatsapp-server-feature-android-zhuan/api/service/group.go`: 群列表轻量参数、单群响应状态字段和可测试 helper。
- Create `../whatsapp-server-feature-android-zhuan/api/service/group_read_test.go`: 群列表、零群和单群状态字段测试。
- Create `../whatsapp-server-feature-android-zhuan/api/service/group_metadata_summaries.go`: 输入规范化、角色识别、有界 worker pool、逐群摘要服务。
- Create `../whatsapp-server-feature-android-zhuan/api/service/group_metadata_summaries_test.go`: 顺序/去重/并发/部分失败/角色测试。
- Modify `../whatsapp-server-feature-android-zhuan/api/router/router.go`: 注册 summaries 路由。
- Modify `../whatsapp-server-feature-android-zhuan/api/router/router_test.go`: 路由方法和路径契约测试。

### Armada file map

- Modify `armada-api/src/main/java/com/armada/platform/protocol/port/AccountParticipatingGroupPort.java`: 只保留固定账号读取能力。
- Create `armada-api/src/main/java/com/armada/platform/protocol/port/AccountParticipatingGroupBatchPort.java`: 保留 Web-only 多账号批量查群能力。
- Create `armada-api/src/main/java/com/armada/platform/protocol/port/FixedAccountGroupMetadataPort.java`: 接收完整账号引用的只读 metadata 端口。
- Create `armada-api/src/main/java/com/armada/platform/protocol/routing/AccountParticipatingGroupBackend.java`: 固定账号群列表/摘要 backend 契约。
- Create `armada-api/src/main/java/com/armada/platform/protocol/routing/FixedAccountGroupMetadataBackend.java`: 固定账号 metadata backend 契约。
- Create `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupInviteBackend.java`: 固定账号邀请链接 backend 契约。
- Create `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingAccountParticipatingGroupPort.java`: 按账号 backend 路由列表/摘要。
- Create `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingFixedAccountGroupMetadataPort.java`: 按账号 backend 路由 metadata。
- Create `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupInvitePort.java`: 按账号 backend 路由邀请链接。
- Modify `armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapter.java`: 同时作为 Web backend 与 Web-only batch port。
- Modify `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapter.java`: 保留旧端口并增加 Web fixed-account backend 重载。
- Modify `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapter.java`: 作为 Web invite backend。
- Modify `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java`: 增加群列表、摘要、邀请链接原生方法。
- Modify `armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java`: 实现三个 Android HTTP 契约。
- Create `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java`: 映射群列表与摘要。
- Create `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMetadataMapper.java`: 映射单群 metadata 与成员。
- Create `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupInviteMapper.java`: 校验并映射邀请链接。
- Create `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeAccountParticipatingGroupAdapter.java`: Android 列表/摘要 backend。
- Create `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapter.java`: Android metadata backend。
- Create `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapter.java`: Android invite backend。
- Modify `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupMetadataResult.java`: 增加明确的群异常状态和成员修改能力标记。
- Modify `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`: 注册 Web/Android backend 与三个 Routing Port。
- Modify `armada-api/src/main/java/com/armada/group/service/HistoricalGroupProtocolPorts.java`: 区分只读 routed metadata 与写前 Web metadata。
- Modify `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`: 详情走只读 routed metadata，成员写前检查保持旧端口。
- Update focused tests under `armada-api/src/test/java/com/armada/platform/protocol/**` and `armada-api/src/test/java/com/armada/group/service/impl/**`.

### Explicit non-changes

- No database migration.
- No frontend API or Vue change.
- No `armada-protocol` change.
- No SSH, deployment, remote environment, real WhatsApp account, real group, or production data operation.
- No Android participant mutation, puller ADD, or historical marketing enablement.

### Task 1: Add Android lightweight group-list contract

**Files:**
- Modify: `../whatsapp-server-feature-android-zhuan/api/controller/group.go`
- Create: `../whatsapp-server-feature-android-zhuan/api/controller/group_test.go`
- Modify: `../whatsapp-server-feature-android-zhuan/api/service/group.go`
- Create: `../whatsapp-server-feature-android-zhuan/api/service/group_read_test.go`

- [ ] **Step 1: Write the failing controller query-parser tests**

Create `api/controller/group_test.go` in the Android worktree:

```go
package controller

import "testing"

func TestParseIncludeParticipants(t *testing.T) {
	tests := []struct {
		name      string
	raw       string
	want      bool
	wantError bool
	}{
		{name: "missing keeps legacy behavior", raw: "", want: true},
		{name: "explicit false enables lightweight query", raw: "false", want: false},
		{name: "explicit true keeps participants", raw: "true", want: true},
		{name: "invalid boolean is rejected", raw: "no", wantError: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := parseIncludeParticipants(tt.raw)
			if (err != nil) != tt.wantError {
				t.Fatalf("unexpected error state: err=%v wantError=%v", err, tt.wantError)
			}
			if err == nil && got != tt.want {
				t.Fatalf("unexpected includeParticipants: got %v want %v", got, tt.want)
			}
		})
	}
}
```

- [ ] **Step 2: Run the controller test and verify RED**

Run from the Android worktree root:

```bash
go test ./api/controller -run TestParseIncludeParticipants -count=1
```

Expected: FAIL with `undefined: parseIncludeParticipants`.

- [ ] **Step 3: Add the parser and pass the value to the service**

Add `strconv` and `ws-go/api/vo` to `api/controller/group.go` imports, add the helper, and replace `GetAllGroupController` with:

```go
func parseIncludeParticipants(raw string) (bool, error) {
	if raw == "" {
		return true, nil
	}
	return strconv.ParseBool(raw)
}

// GetAllGroupController 获取所有群聊
// @Summary 获取所有群聊
// @Description 获取 WhatsApp 所有群聊
// @Tags 群聊
// @Accept json
// @Produce json
// @Param key path string true "手机号"
// @Param includeParticipants query bool false "是否包含群成员，默认 true"
// @Success 200 {object} vo.Resp "响应结果：0 - 成功；1001 - 系统错误；1002 - 参数错误；1004 - 普通错误"
// @Router /ws/v1/groups/list/{key} [get]
func GetAllGroupController(ctx *gin.Context) {
	includeParticipants, err := parseIncludeParticipants(ctx.Query("includeParticipants"))
	if err != nil {
		resp := vo.ParameterError("includeParticipants", "必须为 true 或 false")
		ctx.JSON(http.StatusBadRequest, &resp)
		return
	}
	resp := service.GetAllGroupService(ctx.Param("key"), includeParticipants)
	ctx.JSON(http.StatusOK, &resp)
}
```

- [ ] **Step 4: Write failing service tests for the forwarded flag and non-null empty list**

Create `api/service/group_read_test.go`:

```go
package service

import (
	"testing"
	"ws-go/internal/service/entity"
)

func TestBuildAllGroupResponseForwardsLightweightFlag(t *testing.T) {
	captured := true
	resp := buildAllGroupResponse(false, func(includeParticipants bool) (entity.IqResult, error) {
		captured = includeParticipants
		return entity.IqResult{GroupInfos: []*entity.GroupInfo{
			{GroupId: "120363one@g.us", Subject: "历史群"},
		}}, nil
	})
	if captured {
		t.Fatal("expected GetAllGroup(false)")
	}
	if resp.Code != 0 {
		t.Fatalf("unexpected response code: %d", resp.Code)
	}
	data := resp.Data.(allGroupResponseData)
	if data.Count != 1 || len(data.GroupInfos) != 1 {
		t.Fatalf("unexpected group response: %#v", data)
	}
}

func TestBuildAllGroupResponseReturnsNonNilEmptySlice(t *testing.T) {
	resp := buildAllGroupResponse(false, func(bool) (entity.IqResult, error) {
		return entity.IqResult{GroupInfos: nil}, nil
	})
	data := resp.Data.(allGroupResponseData)
	if data.GroupInfos == nil {
		t.Fatal("successful zero-group response must contain a non-nil empty slice")
	}
	if data.Count != 0 || len(data.GroupInfos) != 0 {
		t.Fatalf("unexpected zero-group response: %#v", data)
	}
}
```

- [ ] **Step 5: Run the service tests and verify RED**

Run:

```bash
go test ./api/service -run 'TestBuildAllGroupResponse' -count=1
```

Expected: FAIL with undefined `buildAllGroupResponse` and `allGroupResponseData`.

- [ ] **Step 6: Extract the group-list helper and pass `includeParticipants` to `GetAllGroup`**

In `api/service/group.go`, add these focused types/functions and replace `GetAllGroupService`:

```go
type allGroupLoader func(includeParticipants bool) (entity.IqResult, error)

type allGroupResponseData struct {
	Count      int                 `json:"Count"`
	GroupInfos []*entity.GroupInfo `json:"GroupInfos"`
}

func buildAllGroupResponse(includeParticipants bool, load allGroupLoader) vo.Resp {
	iqResult, err := load(includeParticipants)
	if err != nil {
		return vo.AnErrorOccurred(err.Error())
	}
	if iqResult.GetErrorEntityResult() != nil {
		return vo.AnErrorOccurred(fmt.Sprintf("获取所有群组失败, %s, Code: %s",
			iqResult.GetErrorEntityResult().Text(), iqResult.GetErrorEntityResult().Code()))
	}
	groupInfos := iqResult.GetGroupInfos()
	if groupInfos == nil {
		groupInfos = make([]*entity.GroupInfo, 0)
	}
	return vo.Success(allGroupResponseData{
		Count:      len(groupInfos),
		GroupInfos: groupInfos,
	}, "")
}

// GetAllGroupService 获取用户所有群组
func GetAllGroupService(k string, includeParticipants bool) vo.Resp {
	waapp := app.GetValidWSApp(k)
	if waapp == nil {
		return vo.AnErrorOccurred(fmt.Sprintf("账号%s不在线,请确认账号状态后重新登录", k))
	}
	return buildAllGroupResponse(includeParticipants, func(include bool) (entity.IqResult, error) {
		result, err := waapp.GetAllGroup(include).GetResult()
		if err != nil {
			return entity.IqResult{}, err
		}
		iqResult, ok := result.(entity.IqResult)
		if !ok {
			return entity.IqResult{}, fmt.Errorf("获取所有群组响应类型异常")
		}
		return iqResult, nil
	})
}
```

- [ ] **Step 7: Format and run focused tests**

Run:

```bash
gofmt -w api/controller/group.go api/controller/group_test.go api/service/group.go api/service/group_read_test.go
go test ./api/controller ./api/service -run 'TestParseIncludeParticipants|TestBuildAllGroupResponse' -count=1
```

Expected: PASS.

- [ ] **Step 8: Commit the lightweight list contract**

Run from the Android worktree root:

```bash
git add api/controller/group.go api/controller/group_test.go api/service/group.go api/service/group_read_test.go
git commit -m "feat: add lightweight Android group listing"
```

Expected: one Android-repository commit containing only Task 1 files.

### Task 2: Add Android metadata summaries worker and stable response types

**Files:**
- Modify: `../whatsapp-server-feature-android-zhuan/api/dto/dto.go`
- Create: `../whatsapp-server-feature-android-zhuan/api/vo/group_metadata.go`
- Create: `../whatsapp-server-feature-android-zhuan/api/service/group_metadata_summaries.go`
- Create: `../whatsapp-server-feature-android-zhuan/api/service/group_metadata_summaries_test.go`

- [ ] **Step 1: Add request and response types**

Append to `api/dto/dto.go`:

```go
// GroupMetadataSummariesDto 是固定账号批量查询群 metadata 摘要的请求。
type GroupMetadataSummariesDto struct {
	GroupJids   []string `json:"groupJids" binding:"required,min=1,dive,required"`
	Concurrency *int     `json:"concurrency" binding:"omitempty,min=1,max=16"`
}
```

Create `api/vo/group_metadata.go`:

```go
package vo

type GroupMetadataSummary struct {
	GroupJID      string  `json:"groupJid"`
	Success       bool    `json:"success"`
	Error         *string `json:"error"`
	Subject       *string `json:"subject"`
	MemberSize    *int    `json:"memberSize"`
	SelfRole      *string `json:"selfRole"`
	AnnounceOnly  *bool   `json:"announceOnly"`
	StateAbnormal bool    `json:"stateAbnormal"`
}

type GroupMetadataSummariesData struct {
	Total     int                    `json:"total"`
	Succeeded int                    `json:"succeeded"`
	Failed    int                    `json:"failed"`
	Results   []GroupMetadataSummary `json:"results"`
}
```

- [ ] **Step 2: Write failing normalization and role tests**

Create the first part of `api/service/group_metadata_summaries_test.go`:

```go
package service

import (
	"errors"
	"fmt"
	"sync/atomic"
	"testing"
	"time"
	"ws-go/api/dto"
	"ws-go/api/vo"
	"ws-go/internal/service/entity"
)

func intPointer(value int) *int { return &value }

func summaryData(t *testing.T, resp vo.Resp) vo.GroupMetadataSummariesData {
	t.Helper()
	if resp.Code != 0 {
		t.Fatalf("unexpected response: %#v", resp)
	}
	data, ok := resp.Data.(vo.GroupMetadataSummariesData)
	if !ok {
		t.Fatalf("unexpected response data type: %T", resp.Data)
	}
	return data
}

func TestBuildGroupMetadataSummariesCleansDeduplicatesAndPreservesOrder(t *testing.T) {
	request := &dto.GroupMetadataSummariesDto{
		GroupJids: []string{
			" 120363two@g.us ",
			"120363one@g.us",
			"120363two@g.us",
		},
		Concurrency: intPointer(2),
	}
	resp := buildGroupMetadataSummaries("919000000001", request,
		func(groupJID string) (*entity.GroupInfo, error) {
			return &entity.GroupInfo{
				GroupId: groupJID,
				Subject: groupJID,
				Participants: []entity.ParticipantAttr{
					{PhoneNumber: "919000000001", Type: "admin"},
				},
			}, nil
		})
	data := summaryData(t, resp)
	if data.Total != 2 || data.Succeeded != 2 || data.Failed != 0 {
		t.Fatalf("unexpected counters: %#v", data)
	}
	if data.Results[0].GroupJID != "120363two@g.us" || data.Results[1].GroupJID != "120363one@g.us" {
		t.Fatalf("input order was not preserved: %#v", data.Results)
	}
	if data.Results[0].SelfRole == nil || *data.Results[0].SelfRole != "ADMIN" {
		t.Fatalf("unexpected self role: %#v", data.Results[0])
	}
}

func TestBuildGroupMetadataSummariesMapsOwnerMemberAndMissingSelf(t *testing.T) {
	request := &dto.GroupMetadataSummariesDto{
		GroupJids: []string{"120363owner@g.us", "120363member@g.us", "120363missing@g.us"},
	}
	resp := buildGroupMetadataSummaries("919000000001", request,
		func(groupJID string) (*entity.GroupInfo, error) {
			participants := []entity.ParticipantAttr{{PhoneNumber: "919000000001", Type: "member"}}
			if groupJID == "120363owner@g.us" {
				participants[0].Type = "superadmin"
			}
			if groupJID == "120363missing@g.us" {
				participants[0].PhoneNumber = "919000000099"
			}
			return &entity.GroupInfo{GroupId: groupJID, Participants: participants}, nil
		})
	data := summaryData(t, resp)
	if *data.Results[0].SelfRole != "OWNER" || *data.Results[1].SelfRole != "MEMBER" {
		t.Fatalf("unexpected roles: %#v", data.Results)
	}
	missing := data.Results[2]
	if !missing.Success || !missing.StateAbnormal || missing.SelfRole != nil ||
		missing.Error == nil || *missing.Error != "SELF_PARTICIPANT_NOT_FOUND" {
		t.Fatalf("unexpected missing-self result: %#v", missing)
	}
}
```

- [ ] **Step 3: Run the new tests and verify RED**

Run:

```bash
go test ./api/service -run 'TestBuildGroupMetadataSummariesCleans|TestBuildGroupMetadataSummariesMaps' -count=1
```

Expected: FAIL with `undefined: buildGroupMetadataSummaries`.

- [ ] **Step 4: Implement normalization, self-role mapping, and deterministic result mapping**

Create `api/service/group_metadata_summaries.go` with these declarations and helpers:

```go
package service

import (
	"fmt"
	"log"
	"strings"
	"sync"
	"time"
	"ws-go/api/dto"
	"ws-go/api/vo"
	"ws-go/internal/service/app"
	"ws-go/internal/service/entity"
	"ws-go/internal/service/jabber"
)

const (
	defaultMetadataConcurrency = 8
	maxMetadataConcurrency     = 16
	maxMetadataGroupCount      = 500
)

type groupMetadataLoader func(groupJID string) (*entity.GroupInfo, error)

type indexedGroupJID struct {
	index    int
	groupJID string
}

func normalizedMetadataGroupJIDs(values []string) ([]string, error) {
	seen := make(map[string]struct{}, len(values))
	result := make([]string, 0, len(values))
	for _, value := range values {
		groupJID := strings.TrimSpace(value)
		if groupJID == "" || !strings.HasSuffix(groupJID, "@g.us") {
			return nil, fmt.Errorf("群 JID 必须以 @g.us 结尾")
		}
		if _, err := jabber.ParseJID(groupJID); err != nil {
			return nil, fmt.Errorf("群 JID 格式错误")
		}
		if _, exists := seen[groupJID]; exists {
			continue
		}
		seen[groupJID] = struct{}{}
		result = append(result, groupJID)
	}
	if len(result) == 0 || len(result) > maxMetadataGroupCount {
		return nil, fmt.Errorf("去重后的群数量必须在 1 到 %d 之间", maxMetadataGroupCount)
	}
	return result, nil
}

func metadataConcurrency(requested *int, groupCount int) (int, error) {
	concurrency := defaultMetadataConcurrency
	if requested != nil {
		concurrency = *requested
	}
	if concurrency < 1 || concurrency > maxMetadataConcurrency {
		return 0, fmt.Errorf("concurrency 必须在 1 到 %d 之间", maxMetadataConcurrency)
	}
	if concurrency > groupCount {
		concurrency = groupCount
	}
	return concurrency, nil
}

func normalizedParticipantPhone(participant entity.ParticipantAttr) string {
	identity := strings.TrimSpace(participant.PhoneNumber)
	if identity == "" {
		identity = strings.TrimSpace(participant.Jid)
	}
	if at := strings.IndexByte(identity, '@'); at >= 0 {
		identity = identity[:at]
	}
	if device := strings.IndexByte(identity, ':'); device >= 0 {
		identity = identity[:device]
	}
	return strings.TrimPrefix(identity, "+")
}

func metadataSelfRole(wsPhone string, participants []entity.ParticipantAttr) *string {
	for _, participant := range participants {
		if normalizedParticipantPhone(participant) != wsPhone {
			continue
		}
		role := "MEMBER"
		if strings.EqualFold(participant.Type, "superadmin") {
			role = "OWNER"
		} else if strings.EqualFold(participant.Type, "admin") {
			role = "ADMIN"
		}
		return &role
	}
	return nil
}

func stringValue(value string) *string {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil
	}
	return &trimmed
}

func intValue(value int) *int { return &value }
func boolValue(value bool) *bool { return &value }

func metadataSummary(wsPhone, groupJID string, load groupMetadataLoader) vo.GroupMetadataSummary {
	info, err := load(groupJID)
	if err != nil || info == nil {
		errorCode := "METADATA_QUERY_FAILED"
		return vo.GroupMetadataSummary{
			GroupJID: groupJID, Success: false, Error: &errorCode, StateAbnormal: true,
		}
	}
	role := metadataSelfRole(wsPhone, info.Participants)
	result := vo.GroupMetadataSummary{
		GroupJID:      groupJID,
		Success:       true,
		Subject:       stringValue(info.Subject),
		MemberSize:    intValue(len(info.Participants)),
		SelfRole:      role,
		AnnounceOnly:  boolValue(info.Announce),
		StateAbnormal: info.Suspended || info.Terminated,
	}
	if role == nil {
		errorCode := "SELF_PARTICIPANT_NOT_FOUND"
		result.Error = &errorCode
		result.StateAbnormal = true
	}
	return result
}
```

- [ ] **Step 5: Write failing bounded-concurrency and partial-failure tests**

Append to `api/service/group_metadata_summaries_test.go`:

```go
func TestBuildGroupMetadataSummariesBoundsConcurrencyAndKeepsPartialFailures(t *testing.T) {
	request := &dto.GroupMetadataSummariesDto{
		GroupJids: []string{
			"120363one@g.us", "120363two@g.us", "120363three@g.us", "120363four@g.us",
		},
		Concurrency: intPointer(2),
	}
	var active int32
	var maximum int32
	resp := buildGroupMetadataSummaries("919000000001", request,
		func(groupJID string) (*entity.GroupInfo, error) {
			current := atomic.AddInt32(&active, 1)
			for {
				observed := atomic.LoadInt32(&maximum)
				if current <= observed || atomic.CompareAndSwapInt32(&maximum, observed, current) {
					break
				}
			}
			time.Sleep(10 * time.Millisecond)
			atomic.AddInt32(&active, -1)
			if groupJID == "120363two@g.us" {
				return nil, errors.New("socket closed")
			}
			return &entity.GroupInfo{
				GroupId: groupJID,
				Participants: []entity.ParticipantAttr{
					{PhoneNumber: "919000000001", Type: "admin"},
				},
			}, nil
		})
	data := summaryData(t, resp)
	if maximum > 2 {
		t.Fatalf("concurrency exceeded request: %d", maximum)
	}
	if data.Succeeded != 3 || data.Failed != 1 || data.Results[1].Success {
		t.Fatalf("unexpected partial result: %#v", data)
	}
	if data.Results[1].Error == nil || *data.Results[1].Error != "METADATA_QUERY_FAILED" {
		t.Fatalf("raw error leaked or stable error missing: %#v", data.Results[1])
	}
}

func TestBuildGroupMetadataSummariesRejectsInvalidNormalizedInput(t *testing.T) {
	resp := buildGroupMetadataSummaries("919000000001", &dto.GroupMetadataSummariesDto{
		GroupJids: []string{" ", "not-a-group"},
	}, func(string) (*entity.GroupInfo, error) {
		t.Fatal("loader must not run for invalid input")
		return nil, nil
	})
	if resp.Code != vo.ParameterErrorCode {
		t.Fatalf("unexpected response: %#v", resp)
	}
}

func TestBuildGroupMetadataSummariesRejectsOutOfRangeConcurrency(t *testing.T) {
	resp := buildGroupMetadataSummaries("919000000001", &dto.GroupMetadataSummariesDto{
		GroupJids:   []string{"120363one@g.us"},
		Concurrency: intPointer(17),
	}, func(string) (*entity.GroupInfo, error) {
		t.Fatal("loader must not run for invalid concurrency")
		return nil, nil
	})
	if resp.Code != vo.ParameterErrorCode {
		t.Fatalf("unexpected response: %#v", resp)
	}
}

func TestMetadataConcurrencyDefaultsToEight(t *testing.T) {
	got, err := metadataConcurrency(nil, 20)
	if err != nil || got != 8 {
		t.Fatalf("unexpected default concurrency: got=%d err=%v", got, err)
	}
}

func TestBuildGroupMetadataSummariesRejectsMoreThanFiveHundredUniqueGroups(t *testing.T) {
	groupJIDs := make([]string, 0, 501)
	for index := 0; index < 501; index++ {
		groupJIDs = append(groupJIDs, fmt.Sprintf("120363%03d@g.us", index))
	}
	resp := buildGroupMetadataSummaries("919000000001", &dto.GroupMetadataSummariesDto{
		GroupJids: groupJIDs,
	}, func(string) (*entity.GroupInfo, error) {
		t.Fatal("loader must not run above the unique group limit")
		return nil, nil
	})
	if resp.Code != vo.ParameterErrorCode {
		t.Fatalf("unexpected response: %#v", resp)
	}
}
```

- [ ] **Step 6: Run the worker tests and verify RED**

Run:

```bash
go test ./api/service -run 'TestBuildGroupMetadataSummariesBounds|TestBuildGroupMetadataSummariesRejects' -count=1
```

Expected: FAIL because `buildGroupMetadataSummaries` is not defined.

- [ ] **Step 7: Implement the bounded worker pool and top-level counters**

Append this complete function to `api/service/group_metadata_summaries.go`:

```go
func buildGroupMetadataSummaries(
	wsPhone string,
	request *dto.GroupMetadataSummariesDto,
	load groupMetadataLoader,
) vo.Resp {
	startedAt := time.Now()
	groupJIDs, err := normalizedMetadataGroupJIDs(request.GroupJids)
	if err != nil {
		return vo.ParameterError("groupJids", err.Error())
	}
	concurrency, err := metadataConcurrency(request.Concurrency, len(groupJIDs))
	if err != nil {
		return vo.ParameterError("concurrency", err.Error())
	}
	results := make([]vo.GroupMetadataSummary, len(groupJIDs))
	jobs := make(chan indexedGroupJID)
	var workers sync.WaitGroup
	workers.Add(concurrency)
	for worker := 0; worker < concurrency; worker++ {
		go func() {
			defer workers.Done()
			for job := range jobs {
				results[job.index] = metadataSummary(wsPhone, job.groupJID, load)
			}
		}()
	}
	for index, groupJID := range groupJIDs {
		jobs <- indexedGroupJID{index: index, groupJID: groupJID}
	}
	close(jobs)
	workers.Wait()
	succeeded := 0
	for _, result := range results {
		if result.Success {
			succeeded++
		}
	}
	log.Printf(
		"group metadata summaries completed account=%s total=%d succeeded=%d failed=%d elapsed_ms=%d",
		wsPhone,
		len(results),
		succeeded,
		len(results)-succeeded,
		time.Since(startedAt).Milliseconds())
	return vo.Success(vo.GroupMetadataSummariesData{
		Total:     len(results),
		Succeeded: succeeded,
		Failed:    len(results) - succeeded,
		Results:   results,
	}, "")
}
```

- [ ] **Step 8: Format, run the package with the race detector, and commit**

Run:

```bash
gofmt -w api/dto/dto.go api/vo/group_metadata.go api/service/group_metadata_summaries.go api/service/group_metadata_summaries_test.go
go test -race ./api/service -run 'TestBuildGroupMetadataSummaries' -count=1
git add api/dto/dto.go api/vo/group_metadata.go api/service/group_metadata_summaries.go api/service/group_metadata_summaries_test.go
git commit -m "feat: add bounded Android group metadata summaries"
```

Expected: tests PASS under `-race`, then one Android-repository commit.

### Task 3: Expose Android summaries route and enrich single-group metadata

**Files:**
- Modify: `../whatsapp-server-feature-android-zhuan/api/controller/group.go`
- Modify: `../whatsapp-server-feature-android-zhuan/api/service/group_metadata_summaries.go`
- Modify: `../whatsapp-server-feature-android-zhuan/api/service/group.go`
- Modify: `../whatsapp-server-feature-android-zhuan/api/service/group_read_test.go`
- Modify: `../whatsapp-server-feature-android-zhuan/api/router/router.go`
- Modify: `../whatsapp-server-feature-android-zhuan/api/router/router_test.go`

- [ ] **Step 1: Write the failing route contract test**

Append to `api/router/router_test.go`:

```go
func TestGroupMetadataSummariesRouteUsesPostAndPathKey(t *testing.T) {
	routes := NewRouter(nil, false).Routes()
	for _, route := range routes {
		if route.Method == "POST" && route.Path == "/ws/v1/groups/metadata-summaries/:key" {
			return
		}
	}
	t.Fatal("POST /ws/v1/groups/metadata-summaries/:key is not registered")
}
```

- [ ] **Step 2: Run the route test and verify RED**

Run:

```bash
go test ./api/router -run TestGroupMetadataSummariesRouteUsesPostAndPathKey -count=1
```

Expected: FAIL with the route-not-registered message.

- [ ] **Step 3: Add the controller and online-account service wrapper**

Append to `api/controller/group.go`:

```go
// GetGroupMetadataSummariesController 批量获取群 metadata 摘要
// @Summary 批量获取群 metadata 摘要
// @Description 使用固定在线 WhatsApp 账号有界并发查询群 metadata 摘要
// @Tags 群聊
// @Accept json
// @Produce json
// @Param key path string true "手机号"
// @Param data body dto.GroupMetadataSummariesDto true "群 JID 和并发数"
// @Success 200 {object} vo.Resp
// @Router /ws/v1/groups/metadata-summaries/{key} [post]
func GetGroupMetadataSummariesController(ctx *gin.Context) {
	request := &dto.GroupMetadataSummariesDto{}
	if !validateData(ctx, &request) {
		return
	}
	resp := service.GetGroupMetadataSummariesService(ctx.Param("key"), request)
	ctx.JSON(http.StatusOK, &resp)
}
```

Append to `api/service/group_metadata_summaries.go`:

```go
// GetGroupMetadataSummariesService 使用指定在线账号批量读取群摘要。
func GetGroupMetadataSummariesService(k string, request *dto.GroupMetadataSummariesDto) vo.Resp {
	waapp := app.GetValidWSApp(k)
	if waapp == nil {
		return vo.AnErrorOccurred(fmt.Sprintf("账号%s不在线,请确认账号状态后重新登录", k))
	}
	return buildGroupMetadataSummaries(k, request, func(groupJID string) (*entity.GroupInfo, error) {
		jid, err := jabber.ParseJID(groupJID)
		if err != nil {
			return nil, err
		}
		result, err := waapp.GetGroupMember(jid).GetResult()
		if err != nil {
			return nil, err
		}
		iqResult, ok := result.(entity.IqResult)
		if !ok {
			return nil, fmt.Errorf("群 metadata 响应类型异常")
		}
		if iqResult.GetErrorEntityResult() != nil {
			return nil, fmt.Errorf("群 metadata 查询失败 code=%s",
				iqResult.GetErrorEntityResult().Code())
		}
		if iqResult.GetGroupInfo() == nil {
			return nil, fmt.Errorf("群 metadata 响应缺少 GroupInfo")
		}
		return iqResult.GetGroupInfo(), nil
	})
}
```

- [ ] **Step 4: Register the route**

Add this line inside the existing `/groups` block in `api/router/router.go`:

```go
groups.POST("/metadata-summaries/:key", controller.GetGroupMetadataSummariesController)
```

- [ ] **Step 5: Write the failing single-group state-field test**

Append to `api/service/group_read_test.go`:

```go
func TestGroupMemberResponseIncludesAnnouncementAndAbnormalState(t *testing.T) {
	info := &entity.GroupInfo{
		GroupId:    "120363one@g.us",
		Subject:    "历史群",
		Announce:   true,
		Suspended:  true,
		Terminated: false,
		Participants: []entity.ParticipantAttr{
			{PhoneNumber: "919000000001", Type: "superadmin"},
		},
	}
	data := groupMemberResponseData(info)
	if data.AnnounceOnly != true || data.StateAbnormal != true {
		t.Fatalf("state fields missing: %#v", data)
	}
	if data.Count != 1 || data.Participants[0].Phone != "919000000001" ||
		data.Participants[0].Type != "superadmin" {
		t.Fatalf("existing participant shape changed: %#v", data)
	}
}
```

- [ ] **Step 6: Run the state-field test and verify RED**

Run:

```bash
go test ./api/service -run TestGroupMemberResponseIncludesAnnouncementAndAbnormalState -count=1
```

Expected: FAIL with undefined `groupMemberResponseData`.

- [ ] **Step 7: Extract and use the stable member response builder**

Add to `api/service/group.go`:

```go
type groupMemberParticipant struct {
	Phone string `json:"phone"`
	Type  string `json:"type"`
}

type groupMemberData struct {
	Subject       string                   `json:"Subject"`
	GroupID       string                   `json:"GroupId"`
	Creation      string                   `json:"Creation"`
	Creator       string                   `json:"Creator"`
	Count         int                      `json:"Count"`
	Participants  []groupMemberParticipant `json:"Participants"`
	AnnounceOnly  bool                     `json:"AnnounceOnly"`
	StateAbnormal bool                     `json:"StateAbnormal"`
}

func groupMemberResponseData(groupInfo *entity.GroupInfo) groupMemberData {
	participants := make([]groupMemberParticipant, 0, len(groupInfo.Participants))
	for _, participant := range groupInfo.Participants {
		participants = append(participants, groupMemberParticipant{
			Phone: participant.PhoneNumber,
			Type:  participant.Type,
		})
	}
	return groupMemberData{
		Subject:       groupInfo.Subject,
		GroupID:       groupInfo.GroupId,
		Creation:      groupInfo.Creation,
		Creator:       groupInfo.Creator,
		Count:         len(participants),
		Participants:  participants,
		AnnounceOnly:  groupInfo.Announce,
		StateAbnormal: groupInfo.Suspended || groupInfo.Terminated,
	}
}
```

Replace the success-tail of `GetGroupMemberService` after the `groupInfo == nil` guard with:

```go
	return vo.Success(groupMemberResponseData(groupInfo), "ok")
```

- [ ] **Step 8: Format, run the affected Android packages, and commit**

Run:

```bash
gofmt -w api/controller/group.go api/service/group.go api/service/group_read_test.go api/service/group_metadata_summaries.go api/router/router.go api/router/router_test.go
go test ./api/controller ./api/service ./api/router -count=1
git add api/controller/group.go api/service/group.go api/service/group_read_test.go api/service/group_metadata_summaries.go api/router/router.go api/router/router_test.go
git commit -m "feat: expose Android group read metadata contracts"
```

Expected: all three packages PASS, then one Android-repository commit.

### Task 4: Split Armada fixed-account group reads from the Web-only batch API

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/AccountParticipatingGroupPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/AccountParticipatingGroupBatchPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/AccountParticipatingGroupBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingAccountParticipatingGroupPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapter.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapterTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingAccountParticipatingGroupPortTest.java`

- [ ] **Step 1: Write the failing routing test**

Create `RoutingAccountParticipatingGroupPortTest.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingAccountParticipatingGroupPortTest {

    @Test
    void routesBothReadsOnlyToTheAccountBackend() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        RecordingBackend android = new RecordingBackend(ProtocolBackend.ANDROID);
        RoutingAccountParticipatingGroupPort port =
                new RoutingAccountParticipatingGroupPort(List.of(web, android));
        ProtocolAccountRef account = account(ProtocolBackend.ANDROID);

        assertThat(port.listCurrent(account)).extracting(AccountParticipatingGroupResult.Group::groupJid)
                .containsExactly("120363android@g.us");
        assertThat(port.summarize(account, List.of("120363android@g.us"), 8))
                .extracting(AccountGroupMetadataSummaryResult::selfRole)
                .containsExactly("ADMIN");
        assertThat(web.calls).isZero();
        assertThat(android.calls).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateAndMissingBackendRegistrations() {
        RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB);
        assertThatThrownBy(() -> new RoutingAccountParticipatingGroupPort(List.of(web, web)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEB");
        RoutingAccountParticipatingGroupPort port =
                new RoutingAccountParticipatingGroupPort(List.of(web));
        assertThatThrownBy(() -> port.listCurrent(account(ProtocolBackend.ANDROID)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.groups.current");
                });
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(7L, backend, "acc_7", "919000000001");
    }

    private static final class RecordingBackend implements AccountParticipatingGroupBackend {
        private final ProtocolBackend backend;
        private int calls;

        private RecordingBackend(ProtocolBackend backend) {
            this.backend = backend;
        }

        @Override
        public ProtocolBackend backend() {
            return backend;
        }

        @Override
        public List<AccountParticipatingGroupResult.Group> listCurrent(ProtocolAccountRef account) {
            calls++;
            return List.of(new AccountParticipatingGroupResult.Group(
                    "120363android@g.us", "历史群", null, null, null, null));
        }

        @Override
        public List<AccountGroupMetadataSummaryResult> summarize(
                ProtocolAccountRef account, List<String> groupJids, int concurrency) {
            calls++;
            return List.of(new AccountGroupMetadataSummaryResult(
                    groupJids.get(0), true, null, "历史群", 10, "ADMIN", false, false));
        }
    }
}
```

- [ ] **Step 2: Run the routing test and verify RED**

Run from the `armada-api` directory inside the Armada worktree:

```bash
mvn -Dtest=RoutingAccountParticipatingGroupPortTest test
```

Expected: compilation FAIL because backend and routing classes do not exist.

- [ ] **Step 3: Split the batch port and add the backend contract**

Delete `listBatch` from `AccountParticipatingGroupPort.java`. Create `AccountParticipatingGroupBatchPort.java`:

```java
package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import java.util.List;

/** Web 协议多账号批量查群端口；调用方必须已经持有 Web protocolAccountId。 */
public interface AccountParticipatingGroupBatchPort {
    List<AccountParticipatingGroupResult> listBatch(
            List<String> protocolAccountIds,
            int concurrency);
}
```

Create `AccountParticipatingGroupBackend.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import java.util.List;

public interface AccountParticipatingGroupBackend {
    ProtocolBackend backend();

    List<AccountParticipatingGroupResult.Group> listCurrent(ProtocolAccountRef account);

    List<AccountGroupMetadataSummaryResult> summarize(
            ProtocolAccountRef account,
            List<String> groupJids,
            int concurrency);
}
```

- [ ] **Step 4: Implement the routing port**

Create `RoutingAccountParticipatingGroupPort.java` using the established routed-port invariant:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RoutingAccountParticipatingGroupPort
        implements AccountParticipatingGroupPort {

    private final Map<ProtocolBackend, AccountParticipatingGroupBackend> backends;

    public RoutingAccountParticipatingGroupPort(
            List<AccountParticipatingGroupBackend> candidates) {
        EnumMap<ProtocolBackend, AccountParticipatingGroupBackend> mapped =
                new EnumMap<>(ProtocolBackend.class);
        for (AccountParticipatingGroupBackend candidate : candidates) {
            AccountParticipatingGroupBackend previous = mapped.put(candidate.backend(), candidate);
            if (previous != null) {
                throw new IllegalStateException(
                        "账号参与群 backend 重复注册: " + candidate.backend());
            }
        }
        this.backends = Map.copyOf(mapped);
    }

    @Override
    public List<AccountParticipatingGroupResult.Group> listCurrent(ProtocolAccountRef account) {
        return required(account, "account.groups.current").listCurrent(account);
    }

    @Override
    public List<AccountGroupMetadataSummaryResult> summarize(
            ProtocolAccountRef account, List<String> groupJids, int concurrency) {
        return required(account, "account.groups.metadata-summaries")
                .summarize(account, groupJids, concurrency);
    }

    private AccountParticipatingGroupBackend required(
            ProtocolAccountRef account, String operation) {
        ProtocolBackend backend = account == null ? null : account.backend();
        AccountParticipatingGroupBackend selected = backends.get(backend);
        if (selected == null) {
            ProtocolException exception = new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "账号参与群 backend 未注册: " + backend);
            throw backend == null
                    ? exception
                    : exception.withContext(backend, operation,
                            "armada-account:" + account.armadaAccountId());
        }
        return selected;
    }
}
```

- [ ] **Step 5: Make the Web adapter implement both focused interfaces**

Change the class declaration in `HttpAccountParticipatingGroupAdapter.java` to:

```java
public class HttpAccountParticipatingGroupAdapter
        implements AccountParticipatingGroupBackend, AccountParticipatingGroupBatchPort {
```

Add the imports and method:

```java
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.AccountParticipatingGroupBatchPort;
import com.armada.platform.protocol.routing.AccountParticipatingGroupBackend;

@Override
public ProtocolBackend backend() {
    return ProtocolBackend.WEB;
}
```

In `HttpAccountParticipatingGroupAdapterTest.java`, declare fixed-account variables as `AccountParticipatingGroupBackend` and batch variables as `AccountParticipatingGroupBatchPort`; keep every existing HTTP assertion unchanged.

- [ ] **Step 6: Run focused tests and commit**

Run:

```bash
mvn -Dtest=RoutingAccountParticipatingGroupPortTest,HttpAccountParticipatingGroupAdapterTest test
git add armada-api/src/main/java/com/armada/platform/protocol/port/AccountParticipatingGroupPort.java armada-api/src/main/java/com/armada/platform/protocol/port/AccountParticipatingGroupBatchPort.java armada-api/src/main/java/com/armada/platform/protocol/routing/AccountParticipatingGroupBackend.java armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingAccountParticipatingGroupPort.java armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapter.java armada-api/src/test/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapterTest.java armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingAccountParticipatingGroupPortTest.java
git commit -m "refactor: route fixed-account group reads"
```

Expected: both test classes PASS, then one Armada commit.

### Task 5: Add routed fixed-account metadata and invitation ports

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/FixedAccountGroupMetadataPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/FixedAccountGroupMetadataBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingFixedAccountGroupMetadataPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/GroupInviteBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupInvitePort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupMetadataResult.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingFixedAccountGroupMetadataPortTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupInvitePortTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapterTest.java`

- [ ] **Step 1: Write failing routing tests for metadata and invite**

Create `RoutingFixedAccountGroupMetadataPortTest.java` and `RoutingGroupInvitePortTest.java`. The metadata test must call an Android account and assert only the Android recording backend receives it; it must also exercise duplicate and missing backend registration. Use this exact recording result:

```java
new GroupMetadataResult(
        "120363android@g.us",
        "历史群",
        true,
        null,
        null,
        null,
        null,
        null,
        false,
        "Android 未提供 inviteViaLink 设置状态",
        false,
        false,
        List.of())
```

The invite test must return and assert:

```java
new GroupInviteResult(
        "120363android@g.us",
        "ABC123",
        "https://chat.whatsapp.com/ABC123")
```

Both missing-backend assertions must require `ProtocolErrorCode.UNSUPPORTED_BACKEND` and the selected `ProtocolBackend.ANDROID` in exception context.

- [ ] **Step 2: Run the two routing tests and verify RED**

Run:

```bash
mvn -Dtest=RoutingFixedAccountGroupMetadataPortTest,RoutingGroupInvitePortTest test
```

Expected: compilation FAIL because the two backend and routing families do not exist.

- [ ] **Step 3: Add metadata port/backend and its routing implementation**

Create `FixedAccountGroupMetadataPort.java`:

```java
package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupMetadataResult;

/** 固定操作账号只读群 metadata 端口。 */
public interface FixedAccountGroupMetadataPort {
    GroupMetadataResult getMetadata(ProtocolAccountRef account, String groupJid);
}
```

Create `FixedAccountGroupMetadataBackend.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;

public interface FixedAccountGroupMetadataBackend {
    ProtocolBackend backend();
    GroupMetadataResult getMetadata(ProtocolAccountRef account, String groupJid);
}
```

Create `RoutingFixedAccountGroupMetadataPort.java` with an `EnumMap` constructor that rejects duplicates, and this public method/operation name:

```java
@Override
public GroupMetadataResult getMetadata(ProtocolAccountRef account, String groupJid) {
    ProtocolBackend backend = account == null ? null : account.backend();
    FixedAccountGroupMetadataBackend selected = backends.get(backend);
    if (selected == null) {
        ProtocolException exception = new ProtocolException(
                ProtocolErrorCode.UNSUPPORTED_BACKEND,
                "固定账号群 metadata backend 未注册: " + backend);
        throw backend == null
                ? exception
                : exception.withContext(
                        backend,
                        "group.metadata.get",
                        "armada-account:" + account.armadaAccountId());
    }
    return selected.getMetadata(account, groupJid);
}
```

The class implements `FixedAccountGroupMetadataPort`; its `backends` field is `Map<ProtocolBackend, FixedAccountGroupMetadataBackend>` and is assigned with `Map.copyOf(mapped)` after duplicate validation.

- [ ] **Step 4: Add invite backend and routing implementation**

Create `GroupInviteBackend.java`:

```java
package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;

public interface GroupInviteBackend {
    ProtocolBackend backend();
    GroupInviteResult getInvite(ProtocolAccountRef account, String groupJid);
}
```

Create `RoutingGroupInvitePort.java` with duplicate-safe `EnumMap` construction and this routing method:

```java
@Override
public GroupInviteResult getInvite(ProtocolAccountRef account, String groupJid) {
    ProtocolBackend backend = account == null ? null : account.backend();
    GroupInviteBackend selected = backends.get(backend);
    if (selected == null) {
        ProtocolException exception = new ProtocolException(
                ProtocolErrorCode.UNSUPPORTED_BACKEND,
                "群邀请链接 backend 未注册: " + backend);
        throw backend == null
                ? exception
                : exception.withContext(
                        backend,
                        "group.invite.get",
                        "armada-account:" + account.armadaAccountId());
    }
    return selected.getInvite(account, groupJid);
}
```

The class implements `GroupInvitePort`; its field type is `Map<ProtocolBackend, GroupInviteBackend>`.

- [ ] **Step 5: Extend the stable metadata model with abnormal state and mutation capability**

Add these components immediately before `participants` in `GroupMetadataResult.java`:

```java
boolean stateAbnormal,
boolean participantMutationSupported,
```

Update its Javadoc with:

```java
 * @param stateAbnormal                 协议是否明确报告群 suspended/terminated/banned
 * @param participantMutationSupported  当前协议是否已接入历史群成员修改
```

Update all four constructor sites found by `rg -n 'new GroupMetadataResult\(' armada-api/src` so Web production and Web fixtures use `stateAbnormal=false` and `participantMutationSupported=true`. In `HttpGroupMetadataAdapter`, add `Boolean isBanned` to `MetadataResponse`, map `Boolean.TRUE.equals(response.isBanned())` into `stateAbnormal`, and map literal `true` into `participantMutationSupported`.

- [ ] **Step 6: Make Web HTTP adapters satisfy the backend interfaces**

Change `HttpGroupMetadataAdapter` to implement both `GroupMetadataPort` and `FixedAccountGroupMetadataBackend`, add `backend()`, and add the complete overload:

```java
@Override
public ProtocolBackend backend() {
    return ProtocolBackend.WEB;
}

@Override
public GroupMetadataResult getMetadata(ProtocolAccountRef account, String groupJid) {
    if (account == null) {
        throw new ProtocolException(
                ProtocolErrorCode.BAD_REQUEST,
                "协议层固定操作账号不能为空");
    }
    return getMetadata(account.protocolAccountId(), groupJid);
}
```

Change `HttpGroupInviteAdapter` to implement only `GroupInviteBackend` (remove `GroupInvitePort` from its class declaration so Spring does not expose a second public port bean) and add:

```java
@Override
public ProtocolBackend backend() {
    return ProtocolBackend.WEB;
}
```

Change both adapter variables in `HttpGroupInviteAdapterTest` from `GroupInvitePort` to `GroupInviteBackend`; the request and result assertions remain byte-for-byte unchanged. Update the adapter class Javadoc link from `GroupInvitePort` to `GroupInviteBackend`.

Extend `HttpGroupMetadataAdapterTest` to type the adapter as both interfaces and assert the fixed-account overload produces the existing request URL.

- [ ] **Step 7: Run focused tests and commit**

Run:

```bash
mvn -Dtest=RoutingFixedAccountGroupMetadataPortTest,RoutingGroupInvitePortTest,HttpGroupMetadataAdapterTest,HttpGroupInviteAdapterTest,GroupDetailServiceImplTest test
git add armada-api/src/main/java/com/armada/platform/protocol/port/FixedAccountGroupMetadataPort.java armada-api/src/main/java/com/armada/platform/protocol/routing/FixedAccountGroupMetadataBackend.java armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingFixedAccountGroupMetadataPort.java armada-api/src/main/java/com/armada/platform/protocol/routing/GroupInviteBackend.java armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupInvitePort.java armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapter.java armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupInviteAdapter.java armada-api/src/main/java/com/armada/platform/protocol/model/result/GroupMetadataResult.java armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingFixedAccountGroupMetadataPortTest.java armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingGroupInvitePortTest.java armada-api/src/test/java/com/armada/platform/protocol/http/group/HttpGroupMetadataAdapterTest.java armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java
git commit -m "refactor: route fixed-account group metadata reads"
```

Expected: all five focused test classes PASS, then one Armada commit.

### Task 6: Extend the Armada Android native HTTP client

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClientTest.java`

- [ ] **Step 1: Write failing HTTP shape assertions**

In `HttpAndroidNativeClientTest.sendsExistingAndroidNativeRequestShapes`, add these expectations before invoking the client:

```java
server.expect(requestTo(
                "http://android.internal/ws/v1/groups/list/919000000001?includeParticipants=false"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(
                "{\"Code\":0,\"Data\":{\"Count\":0,\"GroupInfos\":[]},\"Msg\":\"\"}",
                MediaType.APPLICATION_JSON));
server.expect(requestTo(
                "http://android.internal/ws/v1/groups/metadata-summaries/919000000001"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("""
                {"groupJids":["120363001@g.us"],"concurrency":8}
                """))
        .andRespond(withSuccess("""
                {"Code":0,"Data":{"total":1,"succeeded":1,"failed":0,"results":[]},"Msg":""}
                """, MediaType.APPLICATION_JSON));
server.expect(requestTo(
                "http://android.internal/ws/v1/groups/qrcode/919000000001"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"group_id\":\"120363001@g.us\"}"))
        .andRespond(withSuccess(
                "{\"Code\":0,\"Data\":\"https://chat.whatsapp.com/ABC123\",\"Msg\":\"\"}",
                MediaType.APPLICATION_JSON));
```

Add these invocations before `server.verify()`:

```java
assertThat(client.groups("919000000001", false).code()).isZero();
assertThat(client.metadataSummaries(
        "919000000001", List.of("120363001@g.us"), 8).code()).isZero();
assertThat(client.invite("919000000001", "120363001@g.us").code()).isZero();
```

Add blank/invalid-input assertions for empty summaries, concurrency 0/17, and blank invite JID.

- [ ] **Step 2: Run the client test and verify RED**

Run:

```bash
mvn -Dtest=HttpAndroidNativeClientTest test
```

Expected: compilation FAIL because the three client methods do not exist.

- [ ] **Step 3: Add the AndroidNativeClient methods**

Add these declarations and Javadocs to `AndroidNativeClient.java`:

```java
AndroidResponseEnvelope groups(String wsPhone, boolean includeParticipants);

AndroidResponseEnvelope metadataSummaries(
        String wsPhone,
        List<String> groupJids,
        int concurrency);

AndroidResponseEnvelope invite(String wsPhone, String groupJid);
```

- [ ] **Step 4: Implement the native HTTP requests with strict validation**

Add constants to `HttpAndroidNativeClient.java`:

```java
private static final String GROUPS_URI_PREFIX = "/ws/v1/groups/list/";
private static final String METADATA_SUMMARIES_URI_PREFIX =
        "/ws/v1/groups/metadata-summaries/";
private static final String GROUP_INVITE_URI_PREFIX = "/ws/v1/groups/qrcode/";
private static final int MAX_METADATA_CONCURRENCY = 16;
```

Add these methods:

```java
@Override
public AndroidResponseEnvelope groups(String wsPhone, boolean includeParticipants) {
    return httpExecutor.getTyped(
            GROUPS_URI_PREFIX + requireDigits(wsPhone)
                    + "?includeParticipants=" + includeParticipants,
            AndroidResponseEnvelope.class);
}

@Override
public AndroidResponseEnvelope metadataSummaries(
        String wsPhone, List<String> groupJids, int concurrency) {
    if (concurrency < 1 || concurrency > MAX_METADATA_CONCURRENCY) {
        throw new IllegalArgumentException("concurrency 必须在 1 到 16 之间");
    }
    return httpExecutor.postTyped(
            METADATA_SUMMARIES_URI_PREFIX + requireDigits(wsPhone),
            new MetadataSummariesRequest(requireTexts(groupJids, "groupJids"), concurrency),
            AndroidResponseEnvelope.class);
}

@Override
public AndroidResponseEnvelope invite(String wsPhone, String groupJid) {
    return httpExecutor.postTyped(
            GROUP_INVITE_URI_PREFIX + requireDigits(wsPhone),
            new MembersRequest(requireText(groupJid, GROUP_JID_FIELD)),
            AndroidResponseEnvelope.class);
}

private record MetadataSummariesRequest(List<String> groupJids, int concurrency) {
}
```

- [ ] **Step 5: Run the client test and commit**

Run:

```bash
mvn -Dtest=HttpAndroidNativeClientTest test
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeClient.java armada-api/src/main/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClient.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/HttpAndroidNativeClientTest.java
git commit -m "feat: add Android group read HTTP contracts"
```

Expected: test PASS and one Armada commit.

### Task 7: Map Android group list, summaries, metadata, and invite responses

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMetadataMapper.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupInviteMapper.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupMetadataMapperTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupInviteMapperTest.java`

- [ ] **Step 1: Write failing mapper tests for all accepted wire identities and counters**

The mapper tests must construct `JsonNode` with an `ObjectMapper` and assert these exact cases:

```java
assertThat(groupMapper.mapGroups(json("""
        {"Count":2,"GroupInfos":[
          {"group_id":"120363one@g.us","subject":"群一"},
          {"group_id":"120363two@g.us","subject":"群二"}
        ]}
        """))).extracting(AccountParticipatingGroupResult.Group::groupJid)
        .containsExactly("120363one@g.us", "120363two@g.us");

assertThat(groupMapper.mapSummaries(json("""
        {"total":2,"succeeded":1,"failed":1,"results":[
          {"groupJid":"120363one@g.us","success":true,"subject":"群一",
           "memberSize":3,"selfRole":"ADMIN","announceOnly":true,"stateAbnormal":false},
          {"groupJid":"120363two@g.us","success":false,"error":"METADATA_QUERY_FAILED",
           "subject":null,"memberSize":null,"selfRole":null,"announceOnly":null,"stateAbnormal":true}
        ]}
        """))).hasSize(2);
```

Also assert that a mismatched `Count`, missing `GroupInfos`, mismatched `total/succeeded/failed`, or missing `results` throws `ProtocolException` with `ANDROID_RESPONSE_UNRECOGNIZED`.

The metadata mapper test must use participants carrying `phone`, `phone_number`, `phoneNumber`, and `jid` in four entries and assert all normalize to numeric phone plus `@s.whatsapp.net`; assert `superadmin` is owner, `admin` is admin, and the response maps `AnnounceOnly=true`, `StateAbnormal=true`.

The invite mapper test must accept exactly `https://chat.whatsapp.com/ABC123`, extract `ABC123`, and reject blank, `http://chat.whatsapp.com/ABC123`, a different host, and a link with no code.

- [ ] **Step 2: Run the mapper tests and verify RED**

Run:

```bash
mvn -Dtest=AndroidAccountParticipatingGroupMapperTest,AndroidGroupMetadataMapperTest,AndroidGroupInviteMapperTest test
```

Expected: compilation FAIL because all three mapper classes are missing.

- [ ] **Step 3: Implement the list/summary mapper with structural validation**

Create `AndroidAccountParticipatingGroupMapper.java`. Its public methods must have these signatures:

```java
public List<AccountParticipatingGroupResult.Group> mapGroups(JsonNode data)
public List<AccountGroupMetadataSummaryResult> mapSummaries(JsonNode data)
```

`mapGroups` must require integer `Count`, array `GroupInfos`, and equal count; each group must read `group_id` and `subject` and construct:

```java
new AccountParticipatingGroupResult.Group(groupJid, subject, null, null, null, null)
```

`mapSummaries` must require integer `total/succeeded/failed`, array `results`, `total == results.size()`, `succeeded + failed == total`, and `succeeded == count(success=true)`. Map each item with:

```java
new AccountGroupMetadataSummaryResult(
        text(item, "groupJid"),
        item.path("success").asBoolean(false),
        nullableText(item, "error"),
        nullableText(item, "subject"),
        nullableInteger(item, "memberSize"),
        nullableText(item, "selfRole"),
        nullableBoolean(item, "announceOnly"),
        item.path("stateAbnormal").asBoolean(false))
```

Every structural failure must throw:

```java
new ProtocolException(
        ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
        "Android 群读取响应结构不完整")
```

- [ ] **Step 4: Implement metadata and invite mappers**

Create `AndroidGroupMetadataMapper.java` with constructor injection of `AndroidGroupMemberMapper`. Its `map(JsonNode data, String requestedGroupJid)` must require textual `GroupId`, textual or null `Subject`, boolean `AnnounceOnly`, boolean `StateAbnormal`, and a valid `Participants` array through the existing member mapper. Return:

```java
new GroupMetadataResult(
        groupJid,
        subject,
        announceOnly,
        null,
        null,
        null,
        null,
        null,
        false,
        "Android 当前不支持读取 inviteViaLink 设置状态",
        stateAbnormal,
        false,
        participants)
```

Reject a returned `GroupId` that differs from `requestedGroupJid` after trimming.

Create `AndroidGroupInviteMapper.java` with:

```java
private static final Pattern INVITE_URL = Pattern.compile(
        "^https://chat\\.whatsapp\\.com/([A-Za-z0-9_-]+)$");

public GroupInviteResult map(JsonNode data, String groupJid) {
    String url = data == null || !data.isTextual() ? null : data.asText().trim();
    Matcher matcher = url == null ? null : INVITE_URL.matcher(url);
    if (matcher == null || !matcher.matches()) {
        throw new ProtocolException(
                ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                "Android 群邀请链接响应无效");
    }
    return new GroupInviteResult(groupJid.trim(), matcher.group(1), url);
}
```

- [ ] **Step 5: Run mapper tests and commit**

Run:

```bash
mvn -Dtest=AndroidAccountParticipatingGroupMapperTest,AndroidGroupMetadataMapperTest,AndroidGroupInviteMapperTest,AndroidGroupMemberMapperTest test
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupMetadataMapper.java armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupInviteMapper.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupMetadataMapperTest.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupInviteMapperTest.java
git commit -m "feat: map Android group read responses"
```

Expected: all mapper tests PASS, then one Armada commit.

### Task 8: Implement Android read backends and application-error handling

**Files:**
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeAccountParticipatingGroupAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapter.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapter.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeAccountParticipatingGroupAdapterTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapterTest.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapterTest.java`

- [ ] **Step 1: Write failing backend tests**

For each backend use a mocked `AndroidNativeClient`, real `AndroidResponseDecoder`, real mapper, and `AndroidGroupOperationErrorMapper`. Assert:

```java
verify(client).groups("919000000001", false);
verify(client).metadataSummaries(
        "919000000001", List.of("120363one@g.us"), 8);
verify(client).members("919000000001", "120363one@g.us");
verify(client).invite("919000000001", "120363one@g.us");
```

For every adapter, add a response `{"Code":1003,"Data":null,"Msg":"账号不在线"}` and assert `ProtocolException.errorCode()` is `ACCOUNT_NOT_ONLINE`, `backend()` context is `ANDROID`, and the operation context is respectively:

```text
account.groups.current
account.groups.metadata-summaries
group.metadata.get
group.invite.get
```

Add one null-envelope case and assert `ANDROID_RESPONSE_UNRECOGNIZED` is preserved with Android context.

- [ ] **Step 2: Run backend tests and verify RED**

Run:

```bash
mvn -Dtest=AndroidNativeAccountParticipatingGroupAdapterTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,AndroidNativeGroupInviteAdapterTest test
```

Expected: compilation FAIL because the three adapters do not exist.

- [ ] **Step 3: Implement the account participating-group backend**

Create `AndroidNativeAccountParticipatingGroupAdapter.java` with constructor fields `AndroidNativeClient`, `AndroidResponseDecoder`, `AndroidGroupOperationErrorMapper`, and `AndroidAccountParticipatingGroupMapper`. Implement `backend()` as `ANDROID` and these methods:

```java
@Override
public List<AccountParticipatingGroupResult.Group> listCurrent(ProtocolAccountRef account) {
    return execute(
            account,
            "account.groups.current",
            () -> client.groups(account.wsPhone(), false),
            mapper::mapGroups);
}

@Override
public List<AccountGroupMetadataSummaryResult> summarize(
        ProtocolAccountRef account, List<String> groupJids, int concurrency) {
    return execute(
            account,
            "account.groups.metadata-summaries",
            () -> client.metadataSummaries(account.wsPhone(), groupJids, concurrency),
            mapper::mapSummaries);
}
```

Its private generic executor must decode the envelope, call `errorMapper.toException` on `!response.success()`, pass only `response.data()` to the mapper, and add context to mapper/decoder `ProtocolException` when context is absent:

```java
private <T> T execute(
        ProtocolAccountRef account,
        String operation,
        Supplier<AndroidResponseEnvelope> request,
        Function<JsonNode, T> map) {
    String operationId = "armada-account:" + account.armadaAccountId();
    try {
        AndroidDecodedResponse response = decoder.decode(request.get());
        if (!response.success()) {
            throw errorMapper.toException(response, account, operation, operationId);
        }
        return map.apply(response.data());
    } catch (ProtocolException ex) {
        if (ex.backend().isPresent()) {
            throw ex;
        }
        throw ex.withContext(ProtocolBackend.ANDROID, operation, operationId);
    }
}
```

- [ ] **Step 4: Implement metadata and invite backends**

Create `AndroidNativeFixedAccountGroupMetadataAdapter.java` implementing `FixedAccountGroupMetadataBackend`. In `getMetadata`, decode `client.members(account.wsPhone(), groupJid)`, map application failure with `group.metadata.get`, then call `mapper.map(response.data(), groupJid)`; apply the generic context rule from Step 3.

Create `AndroidNativeGroupInviteAdapter.java` implementing `GroupInviteBackend`. In `getInvite`, decode `client.invite(account.wsPhone(), groupJid)`, map application failure with `group.invite.get`, then call `mapper.map(response.data(), groupJid)`; apply the context rule from Step 3.

Both adapters return `ProtocolBackend.ANDROID` from `backend()` and use operation ID `armada-account:<id>`.

- [ ] **Step 5: Run backend tests and commit**

Run:

```bash
mvn -Dtest=AndroidNativeAccountParticipatingGroupAdapterTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,AndroidNativeGroupInviteAdapterTest test
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeAccountParticipatingGroupAdapter.java armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapter.java armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapter.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeAccountParticipatingGroupAdapterTest.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapterTest.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupInviteAdapterTest.java
git commit -m "feat: add Android historical group read backends"
```

Expected: all three backend tests PASS, then one Armada commit.

### Task 9: Wire the routed ports while keeping Android writes disabled

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`
- Verify the existing `listBatch` references stay confined to the Web adapter and its focused test.

- [ ] **Step 1: Write failing Spring bean assertions**

Extend `ProtocolConfigurationTest` imports and the existing bean-registration test with:

```java
assertThat(context).hasSingleBean(AccountParticipatingGroupPort.class);
assertThat(context).hasSingleBean(AccountParticipatingGroupBatchPort.class);
assertThat(context).hasSingleBean(FixedAccountGroupMetadataPort.class);
assertThat(context).hasSingleBean(GroupInvitePort.class);

assertThat(context.getBeansOfType(AccountParticipatingGroupBackend.class).values())
        .extracting(AccountParticipatingGroupBackend::backend)
        .containsExactlyInAnyOrder(ProtocolBackend.WEB, ProtocolBackend.ANDROID);
assertThat(context.getBeansOfType(FixedAccountGroupMetadataBackend.class).values())
        .extracting(FixedAccountGroupMetadataBackend::backend)
        .containsExactlyInAnyOrder(ProtocolBackend.WEB, ProtocolBackend.ANDROID);
assertThat(context.getBeansOfType(GroupInviteBackend.class).values())
        .extracting(GroupInviteBackend::backend)
        .containsExactlyInAnyOrder(ProtocolBackend.WEB, ProtocolBackend.ANDROID);
```

Also assert the single public ports are instances of their three Routing classes.

- [ ] **Step 2: Run the configuration test and verify RED**

Run:

```bash
mvn -Dtest=ProtocolConfigurationTest test
```

Expected: FAIL because configuration still exposes raw Web adapters and does not register Android read backends.

- [ ] **Step 3: Register account-group Web/Android backends and ports**

Replace the old `accountParticipatingGroupPort` bean with:

```java
@Bean
public HttpAccountParticipatingGroupAdapter webAccountParticipatingGroupBackend(
        ProtocolHttpExecutorRegistry registry) {
    return new HttpAccountParticipatingGroupAdapter(
            registry.required(ProtocolBackend.WEB));
}

@Bean
public AccountParticipatingGroupBackend androidAccountParticipatingGroupBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupOperationErrorMapper errorMapper,
        AndroidAccountParticipatingGroupMapper mapper) {
    return new AndroidNativeAccountParticipatingGroupAdapter(
            client, decoder, errorMapper, mapper);
}

@Bean
public AccountParticipatingGroupPort accountParticipatingGroupPort(
        List<AccountParticipatingGroupBackend> backends) {
    return new RoutingAccountParticipatingGroupPort(backends);
}
```

Register `AndroidAccountParticipatingGroupMapper` as a bean. The concrete Web adapter bean also satisfies `AccountParticipatingGroupBatchPort`; do not create a second instance.

Use these exact mapper beans:

```java
@Bean
public AndroidAccountParticipatingGroupMapper androidAccountParticipatingGroupMapper() {
    return new AndroidAccountParticipatingGroupMapper();
}

@Bean
public AndroidGroupMetadataMapper androidGroupMetadataMapper(
        AndroidGroupMemberMapper memberMapper) {
    return new AndroidGroupMetadataMapper(memberMapper);
}

@Bean
public AndroidGroupInviteMapper androidGroupInviteMapper() {
    return new AndroidGroupInviteMapper();
}
```

- [ ] **Step 4: Register metadata and invite Web/Android backends and routing ports**

Replace the old raw metadata/invite bean methods with concrete Web adapter beans, Android backend beans, and routing beans:

```java
@Bean
public HttpGroupMetadataAdapter webGroupMetadataAdapter(
        ProtocolHttpExecutorRegistry registry) {
    return new HttpGroupMetadataAdapter(registry.required(ProtocolBackend.WEB));
}

@Bean
public FixedAccountGroupMetadataBackend androidFixedAccountGroupMetadataBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupOperationErrorMapper errorMapper,
        AndroidGroupMetadataMapper mapper) {
    return new AndroidNativeFixedAccountGroupMetadataAdapter(
            client, decoder, errorMapper, mapper);
}

@Bean
public FixedAccountGroupMetadataPort fixedAccountGroupMetadataPort(
        List<FixedAccountGroupMetadataBackend> backends) {
    return new RoutingFixedAccountGroupMetadataPort(backends);
}

@Bean
public HttpGroupInviteAdapter webGroupInviteBackend(
        ProtocolHttpExecutorRegistry registry) {
    return new HttpGroupInviteAdapter(registry.required(ProtocolBackend.WEB));
}

@Bean
public GroupInviteBackend androidGroupInviteBackend(
        AndroidNativeClient client,
        AndroidResponseDecoder decoder,
        AndroidGroupOperationErrorMapper errorMapper,
        AndroidGroupInviteMapper mapper) {
    return new AndroidNativeGroupInviteAdapter(client, decoder, errorMapper, mapper);
}

@Bean
public GroupInvitePort groupInvitePort(List<GroupInviteBackend> backends) {
    return new RoutingGroupInvitePort(backends);
}
```

Because `HttpGroupMetadataAdapter` implements both `GroupMetadataPort` and `FixedAccountGroupMetadataBackend`, Spring exposes one legacy write-preflight port and one Web read backend from the concrete bean. `HttpGroupInviteAdapter` implements only `GroupInviteBackend`; therefore `RoutingGroupInvitePort` remains the single `GroupInvitePort` bean.

- [ ] **Step 5: Verify the Web-only listBatch boundary**

Run:

```bash
rg -n '\.listBatch\(' armada-api/src/main armada-api/src/test
```

Expected: the only results are `AccountParticipatingGroupBatchPort`, `HttpAccountParticipatingGroupAdapter`, and the batch cases in `HttpAccountParticipatingGroupAdapterTest`. If any production service appears, change that service's field, constructor parameter, and import to `AccountParticipatingGroupBatchPort` before continuing; current repository inspection on 2026-07-23 found no such production consumer.

- [ ] **Step 6: Run configuration and affected consumer tests, then commit**

Run:

```bash
mvn -Dtest=ProtocolConfigurationTest,HttpAccountParticipatingGroupAdapterTest test
git add armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java
git diff --cached --name-only
git commit -m "feat: wire Android historical group read routing"
```

Expected: focused tests PASS. Before commit, `git diff --cached --name-only` must not show unrelated marketing files from the original worktree.

### Task 10: Switch historical-group reads to the routed metadata port and prove protocol parity

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/HistoricalGroupProtocolPorts.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`

- [ ] **Step 1: Write failing Android refresh parity test**

In `HistoricalGroupServiceImplTest`, add an Android `ProtocolAccountRef` factory and this test using the existing baseline fixture helper:

```java
@Test
void androidRefreshUsesBaselineIntersectionAndExcludesGroupsJoinedAfterImport() {
    ProtocolAccountRef android = stubBaseline(
            7L,
            ProtocolBackend.ANDROID,
            "[\"120363left@g.us\",\"120363still@g.us\"]",
            null);
    when(participatingGroupPort.listCurrent(android)).thenReturn(List.of(
            currentGroup("120363still@g.us", "仍在历史群"),
            currentGroup("120363new@g.us", "导入后新群")));
    when(participatingGroupPort.summarize(
            android, List.of("120363still@g.us"), 8)).thenReturn(List.of(
            summary(
                    "120363still@g.us",
                    true,
                    null,
                    "仍在历史群",
                    12,
                    "ADMIN",
                    true,
                    false)));

    List<HistoricalGroupItemVO> result = service.refreshHistoricalGroups(7L);

    assertThat(result).extracting(HistoricalGroupItemVO::groupJid)
            .containsExactly("120363left@g.us", "120363still@g.us");
    assertThat(result.get(0).membershipState())
            .isEqualTo(HistoricalGroupMembershipState.CURRENT_NOT_IN_GROUP);
    assertThat(result.get(1).membershipState())
            .isEqualTo(HistoricalGroupMembershipState.CURRENT_IN_GROUP);
    assertThat(result.get(1).selfRole()).isEqualTo(HistoricalGroupSelfRole.ADMIN);
    assertThat(result.get(1).speechState()).isEqualTo(SpeechState.ADMIN_CAN_SPEAK);
}
```

Replace the existing `stubBaseline` helper with these exact overloads so all old tests remain Web by default:

```java
private ProtocolAccountRef stubBaseline(
        Long accountId, String groupJidsJson, String subjectsJson) {
    return stubBaseline(accountId, ProtocolBackend.WEB, groupJidsJson, subjectsJson);
}

private ProtocolAccountRef stubBaseline(
        Long accountId,
        ProtocolBackend backend,
        String groupJidsJson,
        String subjectsJson) {
    ProtocolAccountRef account = new ProtocolAccountRef(
            accountId,
            backend,
            backend == ProtocolBackend.WEB
                    ? "acc_" + accountId
                    : "android_" + accountId,
            backend == ProtocolBackend.WEB
                    ? "86138000000" + accountId
                    : "919000000001");
    AccountGroupBaselineRow baseline = new AccountGroupBaselineRow();
    baseline.setAccountId(accountId);
    baseline.setBaselineGroupJidsJson(groupJidsJson);
    baseline.setBaselineGroupSubjectsJson(subjectsJson);
    when(accountLookupService.findActiveProtocolRef(accountId)).thenReturn(Optional.of(account));
    when(membershipMapper.selectAccountBaselineRow(accountId)).thenReturn(baseline);
    return account;
}
```

- [ ] **Step 2: Write failing Android detail and write-scope safety tests**

Add a `FixedAccountGroupMetadataPort readMetadataPort` mock and rename the existing `GroupMetadataPort` mock to `writeMetadataPort`. Inject both into `HistoricalGroupProtocolPorts`, then add these tests:

```java
@Test
void androidDetailUsesRoutedReadMetadataAndKeepsMemberMutationDisabled() {
    ProtocolAccountRef android = stubBaseline(
            8L,
            ProtocolBackend.ANDROID,
            "[\"120363detail@g.us\"]",
            null);
    GroupMetadataResult androidMetadata = new GroupMetadataResult(
            "120363detail@g.us",
            "安卓历史群",
            true,
            null,
            null,
            null,
            null,
            null,
            false,
            "Android 当前不支持读取 inviteViaLink 设置状态",
            true,
            false,
            List.of(
                    new GroupParticipantResult(
                            "919000000001@s.whatsapp.net",
                            "919000000001",
                            true,
                            false,
                            "admin"),
                    new GroupParticipantResult(
                            "919000000002@s.whatsapp.net",
                            "919000000002",
                            false,
                            false,
                            "member")));
    when(readMetadataPort.getMetadata(android, "120363detail@g.us"))
            .thenReturn(androidMetadata);
    when(invitePort.getInvite(android, "120363detail@g.us"))
            .thenReturn(new GroupInviteResult(
                    "120363detail@g.us",
                    "ABC123",
                    "https://chat.whatsapp.com/ABC123"));

    HistoricalGroupDetailVO result =
            service.getHistoricalGroupDetail(8L, "120363detail@g.us");

    assertThat(result.subject()).isEqualTo("安卓历史群");
    assertThat(result.members()).hasSize(2);
    assertThat(result.inviteUrl()).isEqualTo("https://chat.whatsapp.com/ABC123");
    assertThat(result.selfRole()).isEqualTo(HistoricalGroupSelfRole.ADMIN);
    assertThat(result.speechState()).isEqualTo(SpeechState.ABNORMAL);
    assertThat(result.operationAllowed()).isFalse();
    assertThat(result.operationDisabledReason()).isEqualTo("当前协议暂不支持成员管理");
    verifyNoInteractions(writeMetadataPort, participantPort);
}

@Test
void androidParticipantMutationStillUsesLegacyWriteMetadataPort() {
    ProtocolAccountRef android = stubBaseline(
            9L,
            ProtocolBackend.ANDROID,
            "[\"120363detail@g.us\"]",
            null);
    when(writeMetadataPort.getMetadata(
            android.protocolAccountId(), "120363detail@g.us"))
            .thenThrow(new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_BACKEND,
                    "Android 历史群成员管理尚未接入"));

    assertThatThrownBy(() -> service.promoteParticipants(
            new HistoricalGroupParticipantActionDTO(
                    9L,
                    "120363detail@g.us",
                    List.of("919000000002@s.whatsapp.net"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未接入");
    verifyNoInteractions(readMetadataPort, participantPort);
}
```

This locks the first-batch boundary: routed Android metadata is read-only and cannot accidentally flow into the Web participant mutation port.

- [ ] **Step 3: Run the historical-group test and verify RED**

Run:

```bash
mvn -Dtest=HistoricalGroupServiceImplTest test
```

Expected: compilation FAIL because `HistoricalGroupProtocolPorts` does not yet distinguish read and write metadata; after compilation is adjusted, the abnormal-state assertion must fail until production logic uses it.

- [ ] **Step 4: Split read metadata from write-preflight metadata**

Replace `HistoricalGroupProtocolPorts` with this field layout while keeping it a Spring component:

```java
@Component
public record HistoricalGroupProtocolPorts(
        AccountParticipatingGroupPort participatingGroups,
        FixedAccountGroupMetadataPort readMetadata,
        GroupMetadataPort writeMetadata,
        GroupInvitePort invite,
        GroupParticipantPort participants) {
}
```

In `HistoricalGroupServiceImpl.readDetailMetadata`, replace the metadata call with:

```java
protocolPorts.readMetadata().getMetadata(account, groupJid)
```

In `requireActionMetadata`, replace the accessor only, keeping the legacy string-based call:

```java
protocolPorts.writeMetadata().getMetadata(account.protocolAccountId(), groupJid)
```

- [ ] **Step 5: Apply explicit abnormal-state precedence to detail**

Add this service constant:

```java
private static final String PARTICIPANT_MUTATION_UNSUPPORTED_REASON =
        "当前协议暂不支持成员管理";
```

Replace detail operation enablement and disabled-reason construction with:

```java
boolean participantMutationSupported = metadata != null
        && metadata.participantMutationSupported();
boolean operationAllowed = metadata != null
        && participantMutationSupported
        && accountAdmin
        && inviteLookup.inviteUrl() != null;
String disabledReason = errorMessage != null
        ? errorMessage
        : !participantMutationSupported
                ? PARTICIPANT_MUTATION_UNSUPPORTED_REASON
                : accountAdmin ? null : NON_ADMIN_REASON;
```

Replace the detail speech-state expression with:

```java
metadata == null || metadata.stateAbnormal()
        ? SpeechState.ABNORMAL
        : detailSpeechState(metadata.announce(), accountRole)
```

Do not change the existing list-summary state logic, baseline order, intersection, failure fallback, or invite/metadata independent error handling.

- [ ] **Step 6: Update existing fixtures and run the complete historical-group test class**

Update the test constructor to inject both metadata mocks and update existing `GroupMetadataResult` fixtures with `stateAbnormal=false` and `participantMutationSupported=true`. Replace old detail-read stubs `getMetadata(account.protocolAccountId(), groupJid)` with `readMetadataPort.getMetadata(account, groupJid)`; keep member-action stubs on `writeMetadataPort`.

Run:

```bash
mvn -Dtest=HistoricalGroupServiceImplTest test
```

Expected: every existing Web test and the new Android parity/scope tests PASS.

- [ ] **Step 7: Commit the business-layer switch**

Run:

```bash
git add armada-api/src/main/java/com/armada/group/service/HistoricalGroupProtocolPorts.java armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java
git commit -m "feat: enable Android historical group read flow"
```

Expected: one Armada commit containing only the protocol bundle, service, and its test.

### Task 11: Verify both repositories and update the change record

**Files:**
- Modify: `.harness/changes/2026-07-23-android-historical-group-staged-routing.md`
- Verify all files changed in Tasks 1–10.

- [ ] **Step 1: Run Android formatting and focused tests**

From the Android worktree root run:

```bash
gofmt -w api/controller/group.go api/controller/group_test.go api/dto/dto.go api/vo/group_metadata.go api/service/group.go api/service/group_read_test.go api/service/group_metadata_summaries.go api/service/group_metadata_summaries_test.go api/router/router.go api/router/router_test.go
go test -race ./api/controller ./api/service ./api/router -count=1
```

Expected: all focused packages PASS; race detector reports no race.

- [ ] **Step 2: Run Android repository-wide static and test verification**

Run:

```bash
go vet ./...
go build ./...
go test ./...
git diff --check
git status --short
```

Expected: `go vet`, `go build`, `go test`, and diff check exit 0. `git status --short` may show only intentional uncommitted formatting if a commit needs amendment; otherwise it is clean.

- [ ] **Step 3: Run Armada focused regression tests**

From the `armada-api` directory inside the Armada worktree run:

```bash
mvn -Dtest=HttpAndroidNativeClientTest,AndroidAccountParticipatingGroupMapperTest,AndroidGroupMetadataMapperTest,AndroidGroupInviteMapperTest,AndroidNativeAccountParticipatingGroupAdapterTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,AndroidNativeGroupInviteAdapterTest,RoutingAccountParticipatingGroupPortTest,RoutingFixedAccountGroupMetadataPortTest,RoutingGroupInvitePortTest,HttpAccountParticipatingGroupAdapterTest,HttpGroupMetadataAdapterTest,HttpGroupInviteAdapterTest,ProtocolConfigurationTest,HistoricalGroupServiceImplTest,GroupDetailServiceImplTest test
```

Expected: BUILD SUCCESS and all listed classes PASS.

- [ ] **Step 4: Run Armada full tests and diff verification**

Run:

```bash
mvn test
cd ..
git diff --check
git status --short
```

Expected: BUILD SUCCESS and diff check exit 0. If an existing external-dependency test fails, record its exact class, error, and why it is unrelated; do not report the full suite as passing.

- [ ] **Step 5: Re-run the scope guards**

From the Armada worktree root run:

```bash
git diff --name-only ea054f6..HEAD
rg -n 'ProtocolBackend\.ANDROID|backend\(\)' armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java
rg -n 'listBatch' armada-api/src/main/java/com/armada/platform/protocol/port armada-api/src/main/java/com/armada/platform/protocol/routing
```

Expected:

- changed files are limited to this plan's Android read/backend/service/test/doc scope;
- the historical-group business service contains no Android protocol branch;
- `listBatch` exists only on `AccountParticipatingGroupBatchPort` and the Web adapter, never on the routed fixed-account port.

- [ ] **Step 6: Update the change record with real evidence**

In `.harness/changes/2026-07-23-android-historical-group-staged-routing.md`:

- set the status to `第一批代码与自动化测试完成，待用户决定后续批次` only if Steps 1–5 succeeded;
- check the two first-batch implementation items;
- record the exact Android and Armada commit hashes;
- paste the exact verification commands and pass/fail outcomes;
- state explicitly that no deployment, SSH, remote environment, database, frontend, or real-account operation occurred;
- retain second through fourth batch as unchecked follow-up work.

- [ ] **Step 7: Commit the evidence record**

Run from the Armada worktree root:

```bash
git add .harness/changes/2026-07-23-android-historical-group-staged-routing.md
git commit -m "docs: record Android historical group read verification"
```

Expected: one documentation-only Armada commit.

## Acceptance checklist

- Android `GET /ws/v1/groups/list/{wsPhone}?includeParticipants=false` calls `GetAllGroup(false)`; omitting the query retains `true`.
- Android metadata summaries normalize/deduplicate JIDs, preserve first occurrence order, accept 1–500 unique groups, default concurrency to 8, cap it at 16, and never exceed the requested worker count.
- One metadata failure does not fail other groups; top counters match item `success` values.
- Android owner/admin/member and missing-self semantics match the existing Web summary contract.
- Android single-group response contains `AnnounceOnly` and `StateAbnormal` without removing existing fields.
- Armada always uses `ProtocolAccountRef.wsPhone()` for Android HTTP paths and never substitutes Web `protocolAccountId`.
- Web fixed-account list/summary, metadata, and invite calls keep their current URLs and response mapping.
- Routing constructors reject duplicate backends; missing backends return `UNSUPPORTED_BACKEND` with context.
- Historical group refresh returns baseline only, marks `baseline - current` as left, enriches `baseline ∩ current`, and excludes `current - baseline` new groups.
- A whole current-list failure never turns all baseline groups into left groups.
- Detail metadata and invite remain independent reads; one failure does not erase the other successful result.
- Android read metadata does not enable participant promote/demote/remove in this first batch.
- Web metadata returns `participantMutationSupported=true`; Android returns `false`, so the current DTO disables Android write controls without a protocol branch in the service.
- No database, frontend, `armada-protocol`, deployment, SSH, remote, or real-account change occurs.
