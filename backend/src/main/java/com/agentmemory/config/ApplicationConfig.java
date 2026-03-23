package com.agentmemory.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * 应用配置
 */
public class ApplicationConfig {
    
    private final String databaseType;
    private final String sqlitePath;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final int maxPoolSize;
    private final int retentionDays;
    private final int apiPort;
    private final String userHome;
    
    private ApplicationConfig(Config config) {
        this.userHome = System.getProperty("user.home");

        // 数据库类型
        this.databaseType = config.getString("database.type");

        // SQLite 配置
        this.sqlitePath = expandPath(config.getString("database.sqlitePath"));

        // PostgreSQL 配置
        this.jdbcUrl = config.getString("database.url");
        this.jdbcUser = config.getString("database.user");
        this.jdbcPassword = config.getString("database.password");
        this.maxPoolSize = config.getInt("database.poolSize");
        
        // 安全检查：PostgreSQL 模式下必须设置密码
        if (!"sqlite".equalsIgnoreCase(databaseType) && 
            (jdbcPassword == null || jdbcPassword.isEmpty())) {
            System.err.println("警告: PostgreSQL 模式下未设置数据库密码！");
            System.err.println("请设置环境变量 DATABASE_PASSWORD");
            // 开发环境允许继续，生产环境应抛出异常
            // throw new IllegalStateException("数据库密码未设置，请设置 DATABASE_PASSWORD 环境变量");
        }

        // 数据保留天数（默认14天）
        this.retentionDays = config.hasPath("memory.retention.days")
            ? config.getInt("memory.retention.days") : 14;

        // API 端口（默认8080）
        this.apiPort = config.hasPath("api.port")
            ? config.getInt("api.port") : 8080;
    }
    
    public static ApplicationConfig load() {
        Config config = ConfigFactory.load();
        return new ApplicationConfig(config);
    }
    
    public String getDatabaseType() { return databaseType; }
    public String getSqlitePath() { return sqlitePath; }
    public String getJdbcUrl() { return jdbcUrl; }
    public String getJdbcUser() { return jdbcUser; }
    public String getJdbcPassword() { return jdbcPassword; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public int getRetentionDays() { return retentionDays; }
    public int getApiPort() { return apiPort; }
    public String getUserHome() { return userHome; }

    // 会话处理配置（默认值）
    public int getIncrementalThreshold() {
        return 30;
    }

    public int getMaxCacheSize() {
        return 100;
    }
    
    /**
     * 是否使用 SQLite
     */
    public boolean useSqlite() {
        return "sqlite".equalsIgnoreCase(databaseType);
    }
    
    /**
     * 展开路径中的 ~ 符号
     */
    public String expandPath(String path) {
        if (path != null && path.startsWith("~")) {
            return path.replace("~", userHome);
        }
        return path;
    }
}
