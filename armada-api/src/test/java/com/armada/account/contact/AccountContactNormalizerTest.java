package com.armada.account.contact;

import com.armada.account.contact.model.NormalizedContacts;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountContactNormalizerTest {

    private final AccountContactNormalizer normalizer = new AccountContactNormalizer();

    private static AccountContactSnapshot.Contact contact(
            String phone, String fullName, String firstName, String pushName, String businessName) {
        return new AccountContactSnapshot.Contact(
                phone,
                phone == null ? null : phone + "@s.whatsapp.net",
                fullName, firstName, pushName, businessName);
    }

    @Test
    void countsNamedContactsByFullNameOrFirstNameOnly() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", "张三", null, null, null),
                contact("8613800000002", null, "三", null, null),
                contact("8613800000003", null, null, "zhangsan", null),
                contact("8613800000004", null, null, null, null)
        ), 1L));

        assertThat(result.contactNum()).isEqualTo(4);
        // pushName 是对方自己设的展示名，不算「通讯录里有名字」
        assertThat(result.namedNum()).isEqualTo(2);
    }

    @Test
    void mutualCountIsAlwaysZeroUntilProtocolExposesTheFlag() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", "张三", null, null, null)
        ), 1L));

        assertThat(result.mutualNum()).isZero();
        assertThat(result.rows().get(0).mutual()).isFalse();
    }

    @Test
    void deduplicatesByPhoneMergingNonBlankFields() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", null, null, "zhangsan", null),
                contact("8613800000001", "张三", null, null, "某商铺")
        ), 1L));

        assertThat(result.contactNum()).isEqualTo(1);
        NormalizedContacts.Row row = result.rows().get(0);
        assertThat(row.fullName()).isEqualTo("张三");
        assertThat(row.pushName()).isEqualTo("zhangsan");
        assertThat(row.businessName()).isEqualTo("某商铺");
        assertThat(row.named()).isTrue();
    }

    @Test
    void dropsRowsWithoutUsableDigitsOnlyPhone() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact(null, "无号码", null, null, null),
                contact("   ", "空白号码", null, null, null),
                contact("86abc", "非数字", null, null, null),
                contact("8613800000001", "张三", null, null, null)
        ), 1L));

        assertThat(result.contactNum()).isEqualTo(1);
        assertThat(result.rows().get(0).phone()).isEqualTo("8613800000001");
    }

    @Test
    void blankStringsBecomeNullNotEmpty() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                contact("8613800000001", "  ", "", "  ", "")
        ), 1L));

        NormalizedContacts.Row row = result.rows().get(0);
        assertThat(row.fullName()).isNull();
        assertThat(row.firstName()).isNull();
        assertThat(row.pushName()).isNull();
        assertThat(row.businessName()).isNull();
        assertThat(row.named()).isFalse();
    }

    @Test
    void backfillsJidWhenProtocolOmitsIt() {
        NormalizedContacts result = normalizer.normalize(new AccountContactSnapshot(List.of(
                new AccountContactSnapshot.Contact(
                        "8613800000001", null, "张三", null, null, null)
        ), 1L));

        assertThat(result.rows().get(0).jid()).isEqualTo("8613800000001@s.whatsapp.net");
    }

    @Test
    void emptyAndNullSnapshotsProduceZeroCounts() {
        assertThat(normalizer.normalize(new AccountContactSnapshot(List.of(), 1L)).contactNum())
                .isZero();
        assertThat(normalizer.normalize(new AccountContactSnapshot(null, null)).contactNum())
                .isZero();
        assertThat(normalizer.normalize(null).contactNum()).isZero();
    }
}
