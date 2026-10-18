-- ScheduleTool V3 增量升级；由 MySQL 管理员执行，客户端遇错停止，不使用 --force。
-- 执行前确认本项目 SQL 备份已完成；不重跑 database-init.sql。
-- MySQL DDL 自动提交；失败后保留现场，不重复执行或删除新表。
USE scheduletool;
DELIMITER $$
CREATE PROCEDURE scheduletool_upgrade_mcp_v3_1889618128()
SQL SECURITY INVOKER
BEGIN
    IF (SELECT COUNT(*) FROM flyway_schema_history) <> 2
       OR (SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND
           ((version='1' AND checksum=-799209981) OR (version='2' AND checksum=-130792381))) <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected successful ScheduleTool V1/V2 only; stop and inspect';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='scheduletool'
        AND TABLE_NAME IN ('oauth2_authorization','oauth2_authorization_consent')) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='OAuth tables already exist; stop and inspect';
    END IF;
    IF (SELECT COUNT(*) FROM mysql.user WHERE User='scheduletool_prod' AND Host='REPLACE_WITH_APP_HOST') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected existing dedicated application account';
    END IF;
-- Spring Authorization Server 1.5.8 JDBC schema (Apache-2.0).
-- Only ScheduleTool schema; no new database or MySQL account.
/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'blob' to 'text', as PostgreSQL does not support the 'blob' data type.
        - update ALL columns defined with 'timestamp' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes blob DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value blob DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata blob DEFAULT NULL,
    access_token_value blob DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata blob DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value blob DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata blob DEFAULT NULL,
    refresh_token_value blob DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata blob DEFAULT NULL,
    user_code_value blob DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata blob DEFAULT NULL,
    device_code_value blob DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata blob DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

    GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.oauth2_authorization TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
    GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.oauth2_authorization_consent TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
    INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
        VALUES(3,'3','mcp oauth','SQL','V3__mcp_oauth.sql',1889618128,CURRENT_USER(),0,1);
END$$
CALL scheduletool_upgrade_mcp_v3_1889618128()$$
DROP PROCEDURE scheduletool_upgrade_mcp_v3_1889618128$$
DELIMITER ;
SELECT version,description,checksum,success FROM flyway_schema_history ORDER BY installed_rank;
SHOW GRANTS FOR 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
