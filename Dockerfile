# ============================================================
# 多阶段构建：
#   阶段1 build —— Maven + JDK17 编译打包（产物瘦身，镜像里不保留 JDK/Maven）
#   阶段2 runtime —— 仅 JRE17 运行 jar
# 注意：密钥不入镜像。运行时把宿主机 src/main/resources/application-local.properties
# 只读挂载到 /app/config/ 下，Spring Boot 会自动加载（见 docker-compose.yml）。
# ============================================================

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷贝 pom.xml，利用 Docker 层缓存：依赖未变时无需重新下载
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# 再拷贝源码并打包（跳过测试）
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------------- 运行阶段 ----------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Spring Boot 默认配置搜索路径包含 file:./config/，运行时挂载的
# application-local.properties 放在这里即可被自动加载
RUN mkdir -p /app/config

COPY --from=build /build/target/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
