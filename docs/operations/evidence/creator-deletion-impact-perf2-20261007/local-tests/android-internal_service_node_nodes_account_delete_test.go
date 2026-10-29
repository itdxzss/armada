package nodes

import (
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pkg/errors"
	"ws-go/internal/service/xmpp"
)

func deleteAccountReply(id, responseType, from string, children ...*xmpp.Node) *xmpp.Node {
	n := xmpp.EmptyNode(NodeIq,
		xmpp.NewAttribute("id", id),
		xmpp.NewAttribute("type", responseType),
		xmpp.NewAttribute("from", from))
	for _, child := range children {
		n.Children.AddNode(child)
	}
	return n
}

func TestAccountDeleteIQValidatesResponse(t *testing.T) {
	tests := []struct {
		name    string
		reply   *xmpp.Node
		outcome string
		reason  string
		code    string
	}{
		{"accepted empty result", deleteAccountReply("42", "result", "s.whatsapp.net"), AccountDeleteAccepted, "", ""},
		{"accepted empty remove", deleteAccountReply("42", "result", "s.whatsapp.net", xmpp.EmptyNode("remove")), AccountDeleteAccepted, "", ""},
		{"server rejected", deleteAccountReply("42", "error", "s.whatsapp.net", xmpp.EmptyNode("error", xmpp.NewAttribute("code", "401"))), AccountDeleteRejected, "SERVER_REJECTED", "401"},
		{"error without child", deleteAccountReply("42", "error", "s.whatsapp.net"), AccountDeleteRejected, "SERVER_REJECTED", ""},
		{"missing from", deleteAccountReply("42", "result", ""), AccountDeleteUnknown, "INVALID_RESPONSE_FROM", ""},
		{"wrong from", deleteAccountReply("42", "result", "example.invalid"), AccountDeleteUnknown, "INVALID_RESPONSE_FROM", ""},
		{"wrong id", deleteAccountReply("43", "result", "s.whatsapp.net"), AccountDeleteUnknown, "MISMATCHED_RESPONSE_ID", ""},
		{"missing type", deleteAccountReply("42", "", "s.whatsapp.net"), AccountDeleteUnknown, "INVALID_RESPONSE_TYPE", ""},
		{"unknown type", deleteAccountReply("42", "get", "s.whatsapp.net"), AccountDeleteUnknown, "INVALID_RESPONSE_TYPE", ""},
		{"contradictory result", deleteAccountReply("42", "result", "s.whatsapp.net", xmpp.EmptyNode("error")), AccountDeleteUnknown, "UNEXPECTED_RESULT_PAYLOAD", ""},
		{"unrelated result", deleteAccountReply("42", "result", "s.whatsapp.net", xmpp.EmptyNode("group")), AccountDeleteUnknown, "UNEXPECTED_RESULT_PAYLOAD", ""},
		{"remove contains error", deleteAccountReply("42", "result", "s.whatsapp.net", xmpp.EmptyNode("remove", xmpp.NewAttribute("error", "403"))), AccountDeleteUnknown, "UNEXPECTED_RESULT_PAYLOAD", ""},
		{"nil node", nil, AccountDeleteUnknown, "INVALID_RESPONSE_TAG", ""},
		{"wrong tag", xmpp.EmptyNode("ack"), AccountDeleteUnknown, "INVALID_RESPONSE_TAG", ""},
		{"unsafe code is omitted", deleteAccountReply("42", "error", "s.whatsapp.net", xmpp.EmptyNode("error", xmpp.NewAttribute("code", "private-value"))), AccountDeleteRejected, "SERVER_REJECTED", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			request := NewAccountDeleteIQNode(42, time.Now().Add(time.Second))
			if _, err := request.Builder(); err != nil {
				t.Fatalf("请求编码失败: %v", err)
			}
			request.Process(tt.reply)
			result, err := request.GetDeleteAccountResult()
			if result.IQID != "42" || result.Outcome != tt.outcome || result.ErrorCode != tt.code {
				t.Fatalf("返回结果错误: %+v", result)
			}
			if tt.reason == "" {
				if err != nil {
					t.Fatalf("成功 IQ 不应报错: %v", err)
				}
			} else {
				var typed *AccountDeleteIQError
				if !errors.As(err, &typed) || typed.Reason != tt.reason {
					t.Fatalf("错误分类不符: %v", err)
				}
				if strings.Contains(err.Error(), "private-value") {
					t.Fatal("错误回传包含未脱敏内容")
				}
			}
		})
	}
}

func TestAccountDeleteIQStopsQueuedAndRepeatedSends(t *testing.T) {
	request := NewAccountDeleteIQNode(7, time.Now().Add(-time.Second))
	if _, err := request.Builder(); err == nil {
		t.Fatal("已过期节点不能编码发送")
	}
	result, err := request.GetDeleteAccountResult()
	if err == nil || result.Outcome != AccountDeleteNotSent {
		t.Fatalf("编码前过期应为未发送: result=%+v err=%v", result, err)
	}

	request = NewAccountDeleteIQNode(8, time.Now().Add(time.Second))
	if _, err := request.Builder(); err != nil {
		t.Fatal(err)
	}
	if _, err := request.Builder(); err == nil {
		t.Fatal("同一删除请求不能被重复编码发送")
	}
	request.RejectUnconfirmed("TIMEOUT")
	request.Process(deleteAccountReply("8", "result", "s.whatsapp.net"))
	result, err = request.GetDeleteAccountResult()
	if err == nil || result.Outcome != AccountDeleteUnknown {
		t.Fatalf("超时后的迟到结果不能改写未知状态: result=%+v err=%v", result, err)
	}
}

func TestAccountDeleteIQDoesNotAcceptReplyBeforeSend(t *testing.T) {
	request := NewAccountDeleteIQNode(9, time.Now().Add(time.Second))
	request.Process(deleteAccountReply("9", "result", "s.whatsapp.net"))
	result, err := request.GetDeleteAccountResult()
	if err == nil || result.Outcome != AccountDeleteNotSent {
		t.Fatalf("未发送前的回复不应成功: result=%+v err=%v", result, err)
	}
}

func TestAccountDeleteIQConcurrentSettlement(t *testing.T) {
	request := NewAccountDeleteIQNode(10, time.Now().Add(time.Second))
	if _, err := request.Builder(); err != nil {
		t.Fatal(err)
	}
	var cleanupCount int
	var countMu sync.Mutex
	request.OnSettled(func() {
		countMu.Lock()
		cleanupCount++
		countMu.Unlock()
	})
	var wg sync.WaitGroup
	for n := 0; n < 20; n++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			if n%2 == 0 {
				request.RejectUnconfirmed("PROCESSOR_CLOSED")
			} else {
				request.Process(deleteAccountReply("10", "result", "s.whatsapp.net"))
			}
		}(n)
	}
	wg.Wait()
	result, _ := request.GetDeleteAccountResult()
	if result.Outcome != AccountDeleteAccepted && result.Outcome != AccountDeleteUnknown {
		t.Fatalf("并发结果分类错误: %+v", result)
	}
	if cleanupCount != 1 {
		t.Fatalf("清理回调应执行一次，实际 %d", cleanupCount)
	}
}

func TestAuditLateDeletionAcceptedReplyCannotPromoteUnknown(t *testing.T) {
 n:=NewAccountDeleteIQNode(999,time.Now().Add(time.Minute));if _,err:=n.Builder();err!=nil {t.Fatal(err)}
 n.RejectUnconfirmed("CONTEXT_CANCELLED")
 n.Process(deleteAccountReply("999","result","s.whatsapp.net"))
 got,err:=n.GetDeleteAccountResult();if err==nil||got.Outcome!=AccountDeleteUnknown{t.Fatalf("got=%+v err=%v",got,err)}
}
