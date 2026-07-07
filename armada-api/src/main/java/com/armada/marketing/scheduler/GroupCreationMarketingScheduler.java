package com.armada.marketing.scheduler;

import com.armada.marketing.service.impl.GroupCreationMarketingWorker;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka")
public class GroupCreationMarketingScheduler {

    private final GroupCreationMarketingWorker worker;

    public GroupCreationMarketingScheduler(GroupCreationMarketingWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${armada.group-creation-marketing.scheduler.fixed-delay-ms:5000}")
    public void run() {
        worker.processDueItems(20);
    }
}
