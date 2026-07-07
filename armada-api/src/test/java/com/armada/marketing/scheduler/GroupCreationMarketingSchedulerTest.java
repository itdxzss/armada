package com.armada.marketing.scheduler;

import com.armada.marketing.service.impl.GroupCreationMarketingWorker;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GroupCreationMarketingSchedulerTest {

    @Test
    void runProcessesDueItems() {
        GroupCreationMarketingWorker worker = mock(GroupCreationMarketingWorker.class);

        new GroupCreationMarketingScheduler(worker).run();

        verify(worker).processDueItems(20);
    }
}
