package com.armada.task.mapper;

/**
 * 普通群链接执行域在 H2 MySQL 模式下的建表语句。
 *
 * <p>Flyway 脚本不在 H2 上执行，因此这里手工维护与
 * {@code V090__pull_task_normal_link_execution.sql} 新增的 6 张表等价的 DDL。
 * {@code pull_task} 本身不是由 {@code V090} 创建的——它只在 {@code V090} 里被
 * {@code ALTER TABLE} 增加 {@code started_at}/{@code finished_at}/{@code version}
 * 三列；{@code pull_task} 块镜像的是 {@code V078}/{@code V088}/{@code V090} 三个
 * 脚本共同定义出的线上真实表结构，改动前应对照这三个脚本而不是只看 {@code V090}。
 * 两点差异是刻意的：</p>
 * <ul>
 *   <li>省略列级 {@code CHARACTER SET ascii COLLATE ascii_bin}——H2 不支持列级排序规则，
 *       该约束由 {@code PullTaskNormalLinkMigrationSqlTest} 对迁移脚本做结构断言来保证。</li>
 *   <li>原样保留生成列与部分唯一索引——H2 MySQL 模式能正确复现
 *       "生成列为 NULL 时不参与唯一约束"的语义，这是拉手互斥和群链接占用的核心机制。</li>
 *   <li>{@code pull_task.config_json} 在线上（{@code V078}）是 {@code JSON} 类型，这里
 *       镜像为 {@code VARCHAR(4000)}——H2 的 {@code JSON} 字面量需要显式 {@code CAST}，
 *       而本测试套件里没有任何测试读取该列的内部结构，用 {@code VARCHAR} 存整段 JSON
 *       文本足以覆盖现有断言。</li>
 * </ul>
 *
 * <p>改动 V090 新增表的列时必须同步改这里，否则 Mapper 测试会以过期结构通过。</p>
 */
final class PullTaskNormalLinkSchema {

    private PullTaskNormalLinkSchema() {
    }

    /** 拉群任务主表；只含 Mapper 测试用得到的列。 */
    static final String PULL_TASK = """
            CREATE TABLE pull_task (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
                group_source VARCHAR(32),
                task_name VARCHAR(128) NOT NULL,
                group_name VARCHAR(128),
                mode VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'WAIT_START',
                primary_stage VARCHAR(64),
                blocking_reason VARCHAR(255),
                started_at BIGINT,
                finished_at BIGINT,
                version INT NOT NULL DEFAULT 1,
                group_count INT NOT NULL DEFAULT 0,
                expected_pull_count INT NOT NULL DEFAULT 0,
                config_json VARCHAR(4000) NOT NULL,
                operator_name VARCHAR(64),
                created_by BIGINT,
                remark VARCHAR(500),
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                last_business_executed_at BIGINT,
                deleted_at BIGINT
            )
            """;

    /** 普通群链接任务冻结执行配置。 */
    static final String STANDARD_SETTING = """
            CREATE TABLE pull_task_standard_setting (
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                auto_start TINYINT NOT NULL DEFAULT 0,
                material_admin_timing TINYINT NOT NULL,
                pull_count_min INT NOT NULL,
                pull_count_max INT NOT NULL,
                pull_interval_seconds INT NOT NULL,
                puller_count_per_group INT NOT NULL,
                station_count_per_call INT NOT NULL,
                concurrent_group_count INT NOT NULL,
                puller_risk_minutes INT NOT NULL DEFAULT 0,
                required_manager_count INT NOT NULL DEFAULT 0,
                manager_group_id BIGINT NOT NULL,
                puller_group_id BIGINT NOT NULL,
                station_group_id BIGINT NOT NULL,
                manager_group_name VARCHAR(100) NOT NULL,
                puller_group_name VARCHAR(100) NOT NULL,
                station_group_name VARCHAR(100) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (tenant_id, task_id)
            )
            """;

    /** 群链接与 TXT 一对一冻结配对的执行行；含链接跨任务占用生成列。 */
    static final String GROUP_EXECUTION = """
            CREATE TABLE pull_task_group_execution (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                seq INT NOT NULL,
                group_link_id BIGINT,
                normalized_link VARCHAR(255) NOT NULL,
                invite_code VARCHAR(64) NOT NULL,
                source_link_line_no INT NOT NULL,
                group_jid VARCHAR(128),
                source_file_index INT NOT NULL,
                source_file_name VARCHAR(255) NOT NULL,
                total_line_count INT NOT NULL DEFAULT 0,
                valid_member_count INT NOT NULL DEFAULT 0,
                invalid_line_count INT NOT NULL DEFAULT 0,
                duplicate_line_count INT NOT NULL DEFAULT 0,
                execution_status TINYINT NOT NULL DEFAULT 0,
                stage TINYINT NOT NULL DEFAULT 1,
                manual_paused TINYINT NOT NULL DEFAULT 0,
                wait_resource_type TINYINT,
                reason_code VARCHAR(64),
                reason_message VARCHAR(255),
                next_manager_index INT NOT NULL DEFAULT 0,
                next_puller_index INT NOT NULL DEFAULT 0,
                next_run_at BIGINT NOT NULL DEFAULT 0,
                lock_owner VARCHAR(64),
                lock_expires_at BIGINT,
                version INT NOT NULL DEFAULT 1,
                started_at BIGINT,
                finished_at BIGINT,
                last_business_executed_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                link_occupancy_key VARCHAR(255) GENERATED ALWAYS AS (
                    CASE WHEN execution_status IN (1, 2, 3) THEN normalized_link ELSE NULL END
                ),
                CONSTRAINT uq_pull_task_execution_seq UNIQUE (tenant_id, task_id, seq),
                CONSTRAINT uq_pull_task_execution_link UNIQUE (tenant_id, task_id, normalized_link),
                CONSTRAINT uq_pull_task_execution_file UNIQUE (tenant_id, task_id, source_file_index),
                CONSTRAINT uq_pull_task_execution_link_occupancy
                    UNIQUE (tenant_id, link_occupancy_key)
            )
            """;

    /** TXT 料子号码与逐号码入群、提权结果。 */
    static final String MATERIAL_MEMBER = """
            CREATE TABLE pull_task_material_member (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                member_seq INT NOT NULL,
                source_line_no INT NOT NULL,
                normalized_phone VARCHAR(32) NOT NULL,
                admin_required TINYINT NOT NULL DEFAULT 0,
                pull_call_id BIGINT,
                pull_status TINYINT NOT NULL DEFAULT 0,
                pull_reason_code VARCHAR(64),
                pull_reason_message VARCHAR(255),
                wa_jid VARCHAR(128),
                pull_result_at BIGINT,
                admin_status TINYINT NOT NULL DEFAULT 0,
                admin_command_id VARCHAR(64),
                admin_reason_code VARCHAR(64),
                admin_result_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                CONSTRAINT uq_pull_task_material_seq UNIQUE (tenant_id, group_execution_id, member_seq),
                CONSTRAINT uq_pull_task_material_phone
                    UNIQUE (tenant_id, group_execution_id, normalized_phone)
            )
            """;

    /** 角色账号、在群状态与拉手跨任务占用生成列。 */
    static final String GROUP_ACCOUNT = """
            CREATE TABLE pull_task_group_account (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                account_id BIGINT NOT NULL,
                account_phone VARCHAR(32) NOT NULL,
                role_type TINYINT NOT NULL,
                role_seq INT NOT NULL,
                source_type TINYINT NOT NULL DEFAULT 1,
                selection_mode TINYINT NOT NULL DEFAULT 1,
                entry_mode TINYINT,
                membership_status TINYINT NOT NULL DEFAULT 0,
                joined_at BIGINT,
                pull_call_id BIGINT,
                admin_status TINYINT NOT NULL DEFAULT 0,
                availability_status TINYINT NOT NULL DEFAULT 1,
                unavailable_reason_code VARCHAR(64),
                cooldown_until BIGINT,
                occupied_at BIGINT,
                released_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                occupancy_key BIGINT GENERATED ALWAYS AS (
                    CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END
                ),
                CONSTRAINT uq_pull_task_group_account_occupancy UNIQUE (tenant_id, occupancy_key),
                CONSTRAINT uq_pull_task_group_account_role
                    UNIQUE (tenant_id, group_execution_id, role_type, account_id),
                CONSTRAINT uq_pull_task_group_account_seq
                    UNIQUE (tenant_id, group_execution_id, role_type, role_seq)
            )
            """;

    /** 账号动作：保存联系人、邀请入群、踩链接入群。 */
    static final String ACCOUNT_ACTION = """
            CREATE TABLE pull_task_account_action (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                action_type TINYINT NOT NULL,
                actor_group_account_id BIGINT NOT NULL,
                target_group_account_id BIGINT NOT NULL,
                action_status TINYINT NOT NULL DEFAULT 1,
                command_id VARCHAR(64),
                reason_code VARCHAR(64),
                reason_message VARCHAR(255),
                submitted_at BIGINT,
                result_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                CONSTRAINT uq_pull_task_action_pair UNIQUE
                    (tenant_id, group_execution_id, action_type,
                     actor_group_account_id, target_group_account_id),
                CONSTRAINT uq_pull_task_action_command UNIQUE (tenant_id, command_id)
            )
            """;

    /** 单次批量加成员调用。 */
    static final String PULL_CALL = """
            CREATE TABLE pull_task_pull_call (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                task_id BIGINT NOT NULL,
                group_execution_id BIGINT NOT NULL,
                call_seq INT NOT NULL,
                puller_group_account_id BIGINT NOT NULL,
                puller_account_id BIGINT NOT NULL,
                planned_material_count INT NOT NULL,
                planned_station_count INT NOT NULL,
                call_status TINYINT NOT NULL DEFAULT 1,
                command_id VARCHAR(64),
                idempotency_key VARCHAR(64) NOT NULL,
                reason_code VARCHAR(64),
                reason_message VARCHAR(255),
                submitted_at BIGINT,
                result_at BIGINT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                CONSTRAINT uq_pull_task_call_seq UNIQUE (tenant_id, group_execution_id, call_seq),
                CONSTRAINT uq_pull_task_call_idempotency UNIQUE (tenant_id, idempotency_key),
                CONSTRAINT uq_pull_task_call_command UNIQUE (tenant_id, command_id)
            )
            """;

    /**
     * 按依赖顺序返回全部建表语句。
     *
     * @return 建表语句数组
     */
    static String[] all() {
        return new String[] {
            PULL_TASK, STANDARD_SETTING, GROUP_EXECUTION,
            MATERIAL_MEMBER, GROUP_ACCOUNT, ACCOUNT_ACTION, PULL_CALL,
        };
    }
}
