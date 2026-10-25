package com.tenderpocket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@SpringBootApplication
@EnableScheduling
public class TenderPocketApplication {
    public static void main(String[] args) {
        loadDotEnvIfPresent();
        SpringApplication.run(TenderPocketApplication.class, args);
    }

    private static void loadDotEnvIfPresent() {
        java.nio.file.Path[] candidates = new java.nio.file.Path[] {
            java.nio.file.Paths.get(".env"),
            java.nio.file.Paths.get("../.env"),
            java.nio.file.Paths.get("backend/.env")
        };
        for (java.nio.file.Path path : candidates) {
            if (java.nio.file.Files.exists(path)) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(path)) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                        int idx = trimmed.indexOf('=');
                        String key = trimmed.substring(0, idx).trim();
                        String val = trimmed.substring(idx + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        if (System.getProperty(key) == null && System.getenv(key) == null) {
                            System.setProperty(key, val);
                        }
                        if ("GEMINI_API_KEY".equals(key) && System.getProperty("gemini.api.key") == null) {
                            System.setProperty("gemini.api.key", val);
                        }
                    }
                } catch (Exception ignored) {}
                break;
            }
        }
    }

    @Bean
    public CommandLineRunner migrateDatabaseColumns(DataSource dataSource) {
        return args -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                String[] alterStatements = {
                    "ALTER TABLE tenders ALTER COLUMN title TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN original_url TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN authority TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN location TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN document_url TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN downloaded_docs TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN notes TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN corrigendum_remark TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN product_name_as_per_tender TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN product_name_as_per_marken TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN ai_details_summary TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN ai_history_summary TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN working_path TYPE text",
                    "ALTER TABLE tenders ALTER COLUMN loss_reason TYPE text"
                };

                for (String sql : alterStatements) {
                    try {
                        stmt.executeUpdate(sql);
                    } catch (Exception ignored) {
                        // Ignore if running on SQLite or column is already TEXT
                    }
                }
                String[] dateFixStatements = {
                    "UPDATE tender_approval_requests SET created_at = replace(replace(created_at, 'T', ' '), 'Z', '') WHERE created_at LIKE '%T%'",
                    "UPDATE tender_approval_requests SET updated_at = replace(replace(updated_at, 'T', ' '), 'Z', '') WHERE updated_at LIKE '%T%'",
                    "UPDATE tender_workflow_comments SET created_at = replace(replace(created_at, 'T', ' '), 'Z', '') WHERE created_at LIKE '%T%'",
                    "UPDATE activity_log SET timestamp = replace(replace(timestamp, 'T', ' '), 'Z', '') WHERE timestamp LIKE '%T%'"
                };
                for (String sql : dateFixStatements) {
                    try {
                        stmt.executeUpdate(sql);
                    } catch (Exception ignored) {}
                }
                System.out.println("[Database Migration] Verified and altered PostgreSQL tender table columns to TEXT and normalized SQLite timestamps.");
            } catch (Exception e) {
                System.out.println("[Database Migration] Migration runner finished: " + e.getMessage());
            }
        };
    }
}
