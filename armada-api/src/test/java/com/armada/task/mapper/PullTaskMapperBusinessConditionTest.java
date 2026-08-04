package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 防止普通拉群 Mapper 再次把状态机条件固化在 XML 中。 */
class PullTaskMapperBusinessConditionTest {

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern HARDCODED_EQUALITY = Pattern.compile(
            "(?i)\\b(task_type|mode|status|execution_status|stage|manual_paused|"
                    + "wait_resource_type|role_type|source_type|membership_status|admin_status|"
                    + "availability_status|pull_status|call_status|action_status)"
                    + "\\b\\s*=\\s*'?(?!(?:NULL)\\b)([A-Z_]+|[0-9]+)'?");
    private static final Pattern HARDCODED_IN = Pattern.compile(
            "(?i)\\b(task_type|mode|status|execution_status|stage|manual_paused|"
                    + "wait_resource_type|role_type|source_type|membership_status|admin_status|"
                    + "availability_status|pull_status|call_status|action_status)"
                    + "\\b\\s+IN\\s*\\(\\s*'?([A-Z_]+|[0-9]+)'?");
    private static final List<String> MAPPERS = List.of(
            "PullTaskMapper.xml",
            "PullTaskGroupExecutionMapper.xml",
            "PullTaskGroupAccountMapper.xml",
            "PullTaskMaterialMemberMapper.xml",
            "PullTaskAccountActionMapper.xml",
            "PullTaskPullCallMapper.xml",
            "PullTaskStandardReadMapper.xml");

    @Test
    void businessStatusConditionsMustComeFromJavaParameters() throws IOException {
        for (String mapper : MAPPERS) {
            String xml = Files.readString(Path.of(
                    "src/main/resources/mapper/task", mapper));
            String sql = XML_COMMENT.matcher(xml).replaceAll("");
            assertThat(HARDCODED_EQUALITY.matcher(sql).find())
                    .as("%s contains a hardcoded equality status condition", mapper)
                    .isFalse();
            assertThat(HARDCODED_IN.matcher(sql).find())
                    .as("%s contains a hardcoded IN status condition", mapper)
                    .isFalse();
        }
    }
}
