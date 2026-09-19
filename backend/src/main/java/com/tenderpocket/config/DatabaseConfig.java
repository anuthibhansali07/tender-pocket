package com.tenderpocket.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${SPRING_DATASOURCE_USERNAME:${spring.datasource.username:}}")
    private String username;

    @Value("${SPRING_DATASOURCE_PASSWORD:${spring.datasource.password:}}")
    private String password;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();

        String envUrl = System.getenv("SPRING_DATASOURCE_URL");
        if (envUrl == null || envUrl.isEmpty()) {
            envUrl = System.getenv("DATABASE_URL");
        }

        String finalUrl = (envUrl != null && !envUrl.isEmpty()) ? envUrl : dbUrl;

        // Convert postgres:// or postgresql:// URI format to jdbc:postgresql://
        if (finalUrl.startsWith("postgres://")) {
            finalUrl = finalUrl.replace("postgres://", "jdbc:postgresql://");
        } else if (finalUrl.startsWith("postgresql://")) {
            finalUrl = finalUrl.replace("postgresql://", "jdbc:postgresql://");
        }

        // Auto-convert internal Render hostname to external hostname if running locally outside Render cloud
        if (System.getenv("RENDER") == null && finalUrl.contains("dpg-") && !finalUrl.contains(".render.com")) {
            finalUrl = finalUrl.replace("dpg-d9iounv41pts73bisb50-a", "dpg-d9iounv41pts73bisb50-a.singapore-postgres.render.com");
            if (!finalUrl.contains("sslmode=")) {
                finalUrl += (finalUrl.contains("?") ? "&" : "?") + "sslmode=require";
            }
        }

        if (finalUrl.startsWith("jdbc:sqlite:")) {
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl(finalUrl);
            System.out.println("[DatabaseConfig] Configured SQLite DataSource: " + finalUrl);
            return new HikariDataSource(config);
        }

        try {
            // Auto-create database if it doesn't exist on local PostgreSQL server
            createDatabaseIfNotExist(finalUrl, username, password);

            config.setDriverClassName("org.postgresql.Driver");
            config.setJdbcUrl(finalUrl);

            if (username != null && !username.isEmpty()) {
                config.setUsername(username);
            }
            if (password != null && !password.isEmpty()) {
                config.setPassword(password);
            }

            // Parse inline credentials if present (postgres://user:pass@host/db)
            if (finalUrl.contains("@")) {
                try {
                    String clean = finalUrl.replace("jdbc:postgresql://", "");
                    String userInfo = clean.split("@")[0];
                    String[] userPass = userInfo.split(":");
                    if (userPass.length >= 1) config.setUsername(userPass[0]);
                    if (userPass.length >= 2) config.setPassword(userPass[1]);
                    
                    String hostAndDb = clean.split("@")[1];
                    config.setJdbcUrl("jdbc:postgresql://" + hostAndDb);
                } catch (Exception e) {
                    System.err.println("[DatabaseConfig] Error parsing PostgreSQL URL credentials: " + e.getMessage());
                }
            }

            config.setMaximumPoolSize(25);
            config.setMinimumIdle(5);
            config.setConnectionTimeout(15000);
            config.setIdleTimeout(30000);
            config.setMaxLifetime(60000);
            config.setKeepaliveTime(30000);
            config.setLeakDetectionThreshold(60000);

            HikariDataSource ds = new HikariDataSource(config);
            System.out.println("[DatabaseConfig] Configured PostgreSQL DataSource successfully.");
            return ds;
        } catch (Exception e) {
            System.err.println("[DatabaseConfig] PostgreSQL connection failed (" + e.getMessage() + "). Falling back to SQLite database tenders.db");
            HikariConfig sqliteConfig = new HikariConfig();
            sqliteConfig.setDriverClassName("org.sqlite.JDBC");
            
            String envDbPath = System.getenv("DATABASE_PATH");
            String sqlitePath = (envDbPath != null && !envDbPath.isEmpty()) ? envDbPath : "tenders.db";
            if ("tenders.db".equals(sqlitePath) && new java.io.File("../frontend/tenders.db").exists()) {
                sqlitePath = "../frontend/tenders.db";
            }
            System.out.println("[DatabaseConfig] Using SQLite Database Path: " + sqlitePath);
            sqliteConfig.setJdbcUrl("jdbc:sqlite:" + sqlitePath);
            sqliteConfig.setMaximumPoolSize(10);
            return new HikariDataSource(sqliteConfig);
        }
    }

    private void createDatabaseIfNotExist(String jdbcUrl, String user, String pass) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) return;
        try {
            String clean = jdbcUrl.replace("jdbc:postgresql://", "");
            if (clean.contains("@")) clean = clean.split("@")[1];
            String hostPortAndDb = clean.split("\\?")[0];
            String[] parts = hostPortAndDb.split("/");
            if (parts.length < 2) return;
            
            String hostPort = parts[0];
            String dbName = parts[1];

            String baseUrl = "jdbc:postgresql://" + hostPort + "/postgres";
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(baseUrl, user, pass);
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE \"" + dbName + "\"");
                System.out.println("[DatabaseConfig] Auto-created PostgreSQL database: " + dbName);
            } catch (Exception ignored) {
                // Database already exists
            }
        } catch (Exception ignored) {}
    }
}
