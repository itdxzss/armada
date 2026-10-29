package node

import (
	"testing"
	"time"

	"github.com/pkg/errors"
	"ws-go/internal/service/node/nodes"
	"ws-go/internal/service/xmpp"
)

func TestAccountDeletionGateRejectsBusinessAndAllowsDeleteOnExistingSender(t *testing.T) {
	sender, out := contactTestSender(t)
	processor := &MainNodeProcessor{sender: sender}
	processor.SetAccountDeletionBlocked(true)
	business := nodes.NewIqNode(301)
	business.Node = xmpp.EmptyNode("iq", xmpp.NewAttribute("xmlns", "w:g2"), xmpp.NewAttribute("type", "set"))
	sender.SendBuilder(business)
	if _, err := business.GetResult(time.Second); !errors.Is(err, errAccountDeletionBusinessBlocked) {
		t.Fatalf("business was not rejected: %v", err)
	}
	deletion := nodes.NewAccountDeleteIQNode(302, time.Now().Add(time.Second))
	if err := sender.processQueueItem(deletion); err != nil {
		t.Fatalf("deletion blocked: %v", err)
	}
	out.mu.Lock()
	defer out.mu.Unlock()
	if len(out.writes) != 1 {
		t.Fatalf("writes=%d want only deletion", len(out.writes))
	}
}

func TestAccountDeletionGateRejectsAlreadyQueuedBusiness(t *testing.T) {
	sender, out := contactTestSender(t)
	processor := &MainNodeProcessor{sender: sender}
	business := nodes.NewIqNode(303)
	business.Node = xmpp.EmptyNode("iq", xmpp.NewAttribute("xmlns", "w:g2"), xmpp.NewAttribute("type", "set"))
	processor.SetAccountDeletionBlocked(true)
	if err := sender.processQueueItem(business); !errors.Is(err, errAccountDeletionBusinessBlocked) {
		t.Fatalf("queued business allowed: %v", err)
	}
	out.mu.Lock()
	defer out.mu.Unlock()
	if len(out.writes) != 0 {
		t.Fatal("queued business reached socket")
	}
}

func TestAccountDeletionGateRestoresBusinessAfterExplicitRejection(t *testing.T) {
	sender, _ := contactTestSender(t)
	processor := &MainNodeProcessor{sender: sender}
	processor.SetAccountDeletionBlocked(true)
	processor.SetAccountDeletionBlocked(false)
	business := nodes.NewIqNode(304)
	business.Node = xmpp.EmptyNode("iq", xmpp.NewAttribute("xmlns", "w:g2"), xmpp.NewAttribute("type", "set"))
	if err := sender.processQueueItem(business); err != nil {
		t.Fatal(err)
	}
}

func TestAuditDeletionGateBlocksLiveReadsAndAllowsProtocolConvergence(t *testing.T) {
 sender,_:=contactTestSender(t);p:=&MainNodeProcessor{sender:sender};p.SetAccountDeletionBlocked(true)
 for _, tc:=range []struct{name,ns,typ string; allowed bool}{
 {"group read","w:g2","get",false},{"contact read","usync","get",false},
 {"profile read","w:profile:picture","get",false},{"ping","w:p","get",true},
 {"IQ response","w:g2","result",true}} {t.Run(tc.name,func(t *testing.T){
  n:=nodes.NewIqNode(900);n.Node=xmpp.EmptyNode("iq",xmpp.NewAttribute("xmlns",tc.ns),xmpp.NewAttribute("type",tc.typ))
  if got:=sender.accountDeletionAllows(n);got!=tc.allowed{t.Fatalf("allowed=%v",got)}
 })}
 if !sender.accountDeletionAllows(&nodes.AckNode{})||!sender.accountDeletionAllows(&nodes.ReceiptNode{}) {t.Fatal("ACK or receipt blocked")}
}
