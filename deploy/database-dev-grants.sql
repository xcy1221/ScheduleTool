-- 开发库 V1—V3 迁移后由管理员执行；先替换 host、库名和账号，停止应用并备份。
-- Grant table permissions first; then remove the broader database-level DML permissions.
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool_dev.schedule_events TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool_dev.event_exceptions TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool_dev.auth_tokens TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT ON scheduletool_dev.audit_log TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, UPDATE(id) ON scheduletool_dev.audit_lock TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool_dev.oauth2_authorization TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool_dev.oauth2_authorization_consent TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
GRANT SELECT ON scheduletool_dev.flyway_schema_history TO 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
REVOKE IF EXISTS SELECT, INSERT, UPDATE, DELETE ON scheduletool_dev.* FROM 'scheduletool_app'@'REPLACE_WITH_APP_HOST';
