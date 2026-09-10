package com.armada.marketing.script;

import com.armada.marketing.script.service.ScriptRoleAssignment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 随机选号仍须满足所有角色的资格约束，不能重复使用账号或少配角色。 */
class ScriptRoleAssignmentTest {
    @Test void fiveRolesAndOneAccountCannotProduceAnExecutableAssignment() {
        var candidates = new LinkedHashMap<String, List<Long>>();
        for (int i = 1; i <= 5; i++) candidates.put("P" + i, List.of(10L));
        assertThat(ScriptRoleAssignment.assign(candidates)).isEmpty();
    }

    @Test void overlappingCandidatesMustBeRematchedInsteadOfFailingOnAnUnluckyDraw() {
        var candidates = Map.of("P1", List.of(1L, 2L), "P2", List.of(1L), "P3", List.of(2L, 3L));
        for (int i = 0; i < 100; i++) {
            var assignment = ScriptRoleAssignment.assign(candidates).orElseThrow();
            assertThat(assignment).containsEntry("P2", 1L);
            assertThat(Set.copyOf(assignment.values())).hasSize(3);
            assignment.forEach((role, account) -> assertThat(candidates.get(role)).contains(account));
        }
    }

    @Test void enoughAccountsInThePoolDoesNotHideAnImpossibleRoleConstraint() {
        assertThat(ScriptRoleAssignment.assign(Map.of(
                "P1", List.of(1L), "P2", List.of(1L), "P3", List.of(2L, 3L, 4L)))).isEmpty();
    }
}
