package com.armada.task.service;

import com.armada.task.model.dto.DistributionParams;
import com.armada.task.model.dto.PlanRow;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.enums.DistributionMode;
import com.armada.task.model.enums.JoinResultStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class PlanRowGeneratorTest {

    private static final SelectedAccount A = new SelectedAccount(1L, "911");
    private static final SelectedAccount B = new SelectedAccount(2L, "922");
    private static final SelectedAccount C = new SelectedAccount(3L, "933");

    @Test
    void mode1_eachLinkGetsN_roundRobinAcrossLinks() {
        // 方式一:2链接 · N=2 · [A,B,C] → L1:[A,B] L2:[C,A](rr 连续)
        List<PlanRow> rows = PlanRowGenerator.generate(
                new DistributionParams(DistributionMode.FIXED_ACCOUNTS_PER_LINK, 2, 0, 0),
                List.of(A, B, C), List.of("L1", "L2"), List.of());
        assertThat(rows).extracting("account", "link", "status")
                .containsExactly(
                        tuple("911", "L1", JoinResultStatus.PENDING),
                        tuple("922", "L1", JoinResultStatus.PENDING),
                        tuple("933", "L2", JoinResultStatus.PENDING),
                        tuple("911", "L2", JoinResultStatus.PENDING));
        assertThat(rows).extracting("accountId").containsExactly(1L, 2L, 3L, 1L);
    }

    @Test
    void mode2_assignsEachLinkOnce_roundRobinAcrossAccounts() {
        // 方式二:M=3 · K=2 · 5条链接 → A:2,B:2,C:1,单任务内同一链接只分配一个账号。
        List<PlanRow> rows = PlanRowGenerator.generate(
                new DistributionParams(DistributionMode.FIXED_ACCOUNT_MULTI_LINK, 0, 3, 2),
                List.of(A, B, C), List.of("L1", "L2", "L3", "L4", "L5"), List.of());
        assertThat(rows).extracting("account", "link")
                .containsExactly(
                        tuple("911", "L1"), tuple("922", "L2"), tuple("933", "L3"),
                        tuple("911", "L4"), tuple("922", "L5"));
        assertThat(rows).extracting("link").doesNotHaveDuplicates();
    }

    @Test
    void invalidLinks_becomeFailedRows() {
        List<PlanRow> rows = PlanRowGenerator.generate(
                new DistributionParams(DistributionMode.FIXED_ACCOUNTS_PER_LINK, 1, 0, 0),
                List.of(A), List.of(), List.of("not-a-link"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(JoinResultStatus.FAILED);
        assertThat(rows.get(0).account()).isEmpty();
        assertThat(rows.get(0).accountId()).isNull();
        assertThat(rows.get(0).reason()).isEqualTo("非群链接");
    }

    @Test
    void classify_splitsDedupsAndKeepsOrder() {
        LinkClassifier.Classified c = LinkClassifier.classify(
                "https://chat.whatsapp.com/AAA\n\nbad\nhttps://chat.whatsapp.com/AAA\nhttps://chat.whatsapp.com/BBB");
        assertThat(c.valid()).containsExactly("https://chat.whatsapp.com/AAA", "https://chat.whatsapp.com/BBB");
        assertThat(c.invalid()).containsExactly("bad");
    }
}
