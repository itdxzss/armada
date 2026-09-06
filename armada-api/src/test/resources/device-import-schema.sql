-- 只用于 H2 MySQL 模式；列与当前真实 Mapper 对齐，不进入生产 Flyway。
DROP ALL OBJECTS;
CREATE TABLE account_group (
  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100), deleted_at BIGINT,
  system_builtin TINYINT DEFAULT 0, created_at BIGINT, updated_at BIGINT
);
CREATE TABLE account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
  account_type TINYINT NOT NULL, device_os TINYINT, number_source TINYINT, channel_name VARCHAR(128),
  declared_account_type TINYINT NOT NULL, account_type_verify_status TINYINT,
  account_type_verify_source TINYINT, account_type_verified_at BIGINT,
  ownership TINYINT, lease_until BIGINT, account_group_id BIGINT, protocol_id VARCHAR(32),
  protocol_account_id VARCHAR(64), protocol_address VARCHAR(128), priority INT, dispatched_at BIGINT,
  remark VARCHAR(255), created_at BIGINT, updated_at BIGINT, created_by BIGINT, deleted_at BIGINT,
  is_active TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END),
  CONSTRAINT uq_tenant_phone UNIQUE (tenant_id, ws_phone, is_active)
);
CREATE TABLE account_state (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
  proxy_failure_count INT, pull_into_group_count INT, login_state TINYINT,
  created_at BIGINT, updated_at BIGINT, UNIQUE(tenant_id, account_id)
);
CREATE TABLE account_credential (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
  ws_phone VARCHAR(32), cred_format TINYINT, creds_json TEXT, created_at BIGINT, updated_at BIGINT,
  deleted_at BIGINT, UNIQUE(tenant_id, account_id)
);
CREATE TABLE account_import_batch (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_group_id BIGINT NOT NULL,
  source_file_name VARCHAR(255), source_file_type VARCHAR(16), import_format TINYINT, device_os TINYINT,
  account_type TINYINT, ip_region VARCHAR(64), ip_allocation_mode VARCHAR(16), total_rows INT,
  imported_rows INT, duplicate_rows INT, format_error_rows INT, status TINYINT, created_at BIGINT,
  created_by BIGINT, deleted_at BIGINT, login_success INT, login_failed INT, login_abnormal INT
);
CREATE TABLE account_import_detail (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, batch_id BIGINT NOT NULL,
  line_no INT, ws_phone VARCHAR(32), raw_payload CLOB, source_entry_name VARCHAR(512), account_id BIGINT,
  parse_result TINYINT, fail_reason VARCHAR(255), login_result TINYINT, online_phase TINYINT NOT NULL,
  online_dispatched_at BIGINT, login_settled_at BIGINT, dispatch_attempts INT DEFAULT 0,
  login_reason VARCHAR(255), created_at BIGINT
);
INSERT INTO account_group (id, tenant_id, name) VALUES (11, 7, 'test-group'), (12, 8, 'other-test-group');
