# 多阶段构建：后端 Java 应用
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 复制构建产物
COPY --from=builder /app/target/agent-memory-1.0.0-SNAPSHOT.jar app.jar
COPY --from=builder /app/target/lib lib/
COPY --from=builder /app/target/classes/application.conf config/

# 创建前端目录
RUN mkdir -p /app/frontend

# 暴露端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-cp", "app.jar:lib/*", "com.agentmemory.AgentMemoryApplication"]
