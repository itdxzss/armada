package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.armada.task.model.entity.PullTaskGroupExecution;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PullTaskCreatorDeletionProofTest {
    @Test void acceptedStillRequiresIndependentRegistrationAndCreatorCleanup() {
        var work = work();
        assertThat(PullTaskCreatorDeletionProof.cleaned(work, observed("100@s.whatsapp.net", "100", true, false, false), 1000)).isFalse();
        assertThat(PullTaskCreatorDeletionProof.cleaned(work, observed("", "", false, true, true), 1000)).isFalse();
        assertThat(PullTaskCreatorDeletionProof.cleaned(work, observed("", "", false, true, false), 1000)).isTrue();
    }
    @Test void malformedMissingFieldsStaleQueryAndChangedCreationDoNotCountAsCleared() {
        var work = work();
        assertThat(PullTaskCreatorDeletionProof.cleaned(work, observed(null, null, false, true, false), 1000)).isFalse();
        assertThat(PullTaskCreatorDeletionProof.cleaned(work, new CreatorDeletionObservation("g@g.us", 12, "", "", true, true, false, true, false, true, 999), 1000)).isFalse();
        assertThat(PullTaskCreatorDeletionProof.cleaned(work, new CreatorDeletionObservation("g@g.us", 13, "", "", true, true, false, true, false, true, 1000), 1000)).isFalse();
    }
    @Test void takeoverRequiresRealAdminCreatorIdentityAndDistinctAccount() {
        var work = work();
        assertThat(PullTaskCreatorDeletionProof.takeover(work, observed("100@s.whatsapp.net", "100", true, true, true), 1000)).isTrue();
        assertThat(PullTaskCreatorDeletionProof.takeover(work, observed("999@s.whatsapp.net", "999", true, true, true), 1000)).isFalse();
        assertThat(PullTaskCreatorDeletionProof.takeover(work, new CreatorDeletionObservation("g@g.us",12,"100@s.whatsapp.net","100",true,false,true,true,true,true,1000),1000)).isFalse();
    }
    @Test void pendingReasonsListMissingProofWithoutExposingIdentifiers() {
        var work=work();
        assertThat(PullTaskCreatorDeletionProof.pendingReason(work,observed("100@s.whatsapp.net","100",true,true,true),1000))
                .contains("仍注册","仍在群成员中","尚未清空").doesNotContain("100", "g@g.us");
        var invalid=new CreatorDeletionObservation("other@g.us",13,"","",false,false,false,false,false,false,999);
        assertThat(PullTaskCreatorDeletionProof.pendingReason(work,invalid,1000))
                .contains("结构异常","群身份不匹配","不新鲜","创建时间","管理员权限未确认","注册状态未确认")
                .doesNotContain("other@g.us");
        assertThat(PullTaskCreatorDeletionProof.pendingReason(work,null,1000)).contains("查询尚未完成或不可用");
    }

    @Test void resultRequiresMatchingAccountAndOperationAndIqResult() {
        var row = work().deletion();
        assertThat(PullTaskCreatorDeletionProof.matches(row, new CreatorDeletionResult("op","hash","ACCEPTED","iq","result",null))).isTrue();
        assertThat(PullTaskCreatorDeletionProof.matches(row, new CreatorDeletionResult("other","hash","ACCEPTED","iq","result",null))).isFalse();
        assertThat(new CreatorDeletionResult("op","hash","ACCEPTED",null,"result",null).accepted()).isFalse();
    }
    private static CreatorDeletionObservation observed(String creator,String pn,boolean present,boolean known,boolean registered) {
        return new CreatorDeletionObservation("g@g.us",12,creator,pn,true,true,present,known,registered,true,1000);
    }
    private static PullTaskCreatorDeletionWork work() {
        var execution = new PullTaskGroupExecution(); execution.setGroupJid("g@g.us");
        var row = new PullTaskCreatorDeletion(); row.setCreationBefore(12L); row.setOperationId("op"); row.setCreatorIdentityHash("hash");
        return new PullTaskCreatorDeletionWork(execution,row,new ProtocolAccountRef(1L,ProtocolBackend.ANDROID,"100","100"),new ProtocolAccountRef(2L,ProtocolBackend.ANDROID,"200","200"));
    }
}
