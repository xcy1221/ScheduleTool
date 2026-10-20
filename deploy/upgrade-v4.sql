-- V3 -> V4；先备份并停止本项目。由维护身份执行，遇错停止，不用 --force。
USE scheduletool;
DELIMITER 
CREATE PROCEDURE scheduletool_upgrade_todo_v4()
SQL SECURITY INVOKER
BEGIN
    IF (SELECT COUNT(*) FROM flyway_schema_history) <> 3 OR
       (SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND
         ((version='1' AND checksum=-799209981) OR (version='2' AND checksum=-130792381) OR (version='3' AND checksum=1889618128))) <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected V1-V3 only; stop and inspect';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('todos','todo_events')) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TODO tables already exist; stop and inspect';
    END IF;
CREATE TABLE todos (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    expected_effect VARCHAR(4000) NOT NULL,
    implementation_idea VARCHAR(4000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
CREATE INDEX idx_todos_status ON todos(status, pinned, updated_at);
CREATE TABLE todo_events (
    series_id VARCHAR(36) PRIMARY KEY,
    todo_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_todo_event FOREIGN KEY(series_id) REFERENCES schedule_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_todo FOREIGN KEY(todo_id) REFERENCES todos(id)
);
CREATE INDEX idx_todo_events_todo ON todo_events(todo_id);

    GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.todos TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
    GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.todo_events TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
    INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
      VALUES(4,'4','todos','SQL','V4__todos.sql',-1327294631,CURRENT_USER(),0,1);
END
CALL scheduletool_upgrade_todo_v4()
DROP PROCEDURE scheduletool_upgrade_todo_v4
DELIMITER ;
SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;
