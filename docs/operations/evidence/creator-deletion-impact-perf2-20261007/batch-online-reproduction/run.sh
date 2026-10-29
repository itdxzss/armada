#!/bin/bash
set -euo pipefail
repo=/Users/daishuaishuai/IdeaProjects/armada/armada-api
audit=/private/tmp/armada-batch-proxy-audit
java17=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home
python3 "$audit/prepare-h2.py"
cp_text=$(cat "$audit/classpath.txt")
"$java17/bin/javac" --release 17 -parameters -implicit:none -cp "$cp_text" -d "$audit/classes" \
  "$repo/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java" \
  "$repo/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java" \
  "$repo/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java" \
  "$repo/src/main/java/com/armada/resource/service/impl/IpProxyOptimisticAllocator.java" \
  "$repo/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java" \
  "$repo/src/main/java/com/armada/platform/protocol/exception/ProtocolAccountCommandRejectedException.java" \
  "$repo/src/main/java/com/armada/platform/kafka/dispatch/ProtocolCommandDispatchTrigger.java" \
  "$repo/src/main/java/com/armada/boot/config/MyBatisConfig.java" \
  "$repo/src/main/java/com/armada/account/service/OnlineAttemptIdGenerator.java" \
  "$repo/src/test/java/com/armada/task/mapper/PullTaskNormalLinkH2Support.java" \
  "$repo/src/test/java/com/armada/task/mapper/PullTaskNormalLinkSchema.java" \
  "$repo/src/test/java/com/armada/testsupport/CreatorDeletionH2Schema.java" \
  "$audit/src/BatchProxyShortageProbe.java"
"$java17/bin/java" -cp "$audit/classes:$repo/src/main/resources:$cp_text" BatchProxyShortageProbe raw | tee "$audit/raw-xml-result.log"
"$java17/bin/java" -cp "$audit/classes:$audit/resources:$repo/src/main/resources:$cp_text" BatchProxyShortageProbe | tee "$audit/adapted-result.log"
