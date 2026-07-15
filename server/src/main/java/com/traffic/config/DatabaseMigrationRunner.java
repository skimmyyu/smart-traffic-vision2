package com.traffic.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Small idempotent migrations for databases created by earlier project versions. */
@Component
public class DatabaseMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("camera_id", "VARCHAR(32) NULL COMMENT '采集摄像头编号'");
        addColumnIfMissing("camera_name", "VARCHAR(64) NULL COMMENT '采集摄像头名称'");
    }

    private void addColumnIfMissing(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plate_records' AND COLUMN_NAME = ?",
                Integer.class,
                column
        );
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE plate_records ADD COLUMN " + column + " " + definition);
        }
    }
}
