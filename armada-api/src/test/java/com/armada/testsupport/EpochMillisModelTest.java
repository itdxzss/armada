package com.armada.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkImportDetail;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.resource.model.entity.IpProxy;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 业务模型/查询入参时间字段统一用 Long epoch 毫秒,不暴露 LocalDateTime。 */
class EpochMillisModelTest {

    @Test
    void legacyBusinessModelsDoNotExposeLocalDateTime() {
        List<Class<?>> classes = List.of(
                MarketingTemplate.class,
                IpProxy.class,
                Tenant.class,
                GroupLink.class,
                GroupLinkLabel.class,
                GroupLinkImportBatch.class,
                GroupLinkImportDetail.class,
                GroupLinkVoRow.class,
                GroupLinkLabelVoRow.class,
                GroupLinkImportDetailVoRow.class,
                GroupLinkLabelQuery.class
        );

        List<String> offenders = new ArrayList<>();
        for (Class<?> clazz : classes) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType().equals(LocalDateTime.class)) {
                    offenders.add(clazz.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(offenders).isEmpty();
    }
}
