package com.armada.marketing.script.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 每群角色无放回随机匹配；协议能力不同的候选通过增广路径重新安排，避免误报缺口。 */
public final class ScriptRoleAssignment {
    private ScriptRoleAssignment() { }

    /** 为所有角色分配不同账号；任一角色无法满足时返回空，不能使用部分结果发送。 */
    public static Optional<Map<String, Long>> assign(Map<String, List<Long>> candidates) {
        Map<String, List<Long>> shuffled = new LinkedHashMap<>();
        candidates.forEach((role, ids) -> {
            var values = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
            Collections.shuffle(values);
            shuffled.put(role, values);
        });
        Map<Long, String> assigned = new HashMap<>();
        for (String role : shuffled.keySet()) {
            if (!match(role, shuffled, assigned, new HashSet<>())) return Optional.empty();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        assigned.forEach((account, role) -> result.put(role, account));
        return Optional.of(Map.copyOf(result));
    }

    private static boolean match(String role, Map<String, List<Long>> candidates,
            Map<Long, String> assigned, Set<Long> visited) {
        for (Long account : candidates.get(role)) {
            if (!visited.add(account)) continue;
            String previous = assigned.get(account);
            if (previous == null || match(previous, candidates, assigned, visited)) {
                assigned.put(account, role);
                return true;
            }
        }
        return false;
    }
}
