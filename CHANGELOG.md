# Changelog

## 2026-08-19 01:17:31 - 修复问题

- **变更概述**：修复 IntelliJ IDEA 缺少 `Minecraft Client` / `Minecraft Server` 启动配置的问题。
- **修改文件**：
  - `.idea/gradle.xml`
  - `.idea/runConfigurations/Minecraft_Client.xml`（由 `ideaSyncTask` 生成）
  - `.idea/runConfigurations/Minecraft_Server.xml`（由 `ideaSyncTask` 生成）
- **变更内容**：
  - 将 `.idea/gradle.xml` 的 `gradleJvm` 从 `#GRADLE_JAVA_HOME`（继承系统 JDK 17）改为 `25`（IDEA 已注册的 JDK 25 SDK，指向 `D:/Java`），使 IDEA 的 Gradle 集成使用 JDK 25，满足 Fabric Loom 的 JVM 21+ 要求。
  - 运行 `gradlew ideaSyncTask` 生成 Loom 的 IDEA 运行配置，得到 `Minecraft Client`（模块 `cubic-cadence.client`）与 `Minecraft Server`（模块 `cubic-cadence.main`）两个启动配置。

## 2026-08-19 00:52:59 - 修复问题

- **变更概述**：修复 Gradle 构建失败（`fabric-loom` 要求 JVM 21+，但构建使用了 Java 17）。
- **修改文件**：
  - `gradle.properties`
- **变更内容**：
  - 在 `gradle.properties` 末尾新增 `org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot`，让 Gradle wrapper 使用已安装的 JDK 25 启动 daemon，满足 Fabric Loom 1.17-SNAPSHOT 的 JVM 21+ 运行时要求，同时保持系统级 `JAVA_HOME`（JDK 17）不变，避免影响其它项目。
