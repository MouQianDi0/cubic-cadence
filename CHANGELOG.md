# Changelog

## 2026-08-19 12:24:10 - 新增功能（阶段 2 本地音频播放与原版音乐独立控制）

- **变更概述**：完成阶段 2 本地 WAV 音频播放验证，将方律音乐接入 Minecraft 26.2 现有 `SoundManager`/`SoundEngine`/OpenAL 输出链，并增加播放、暂停、继续、停止、状态/时长、方律独立音量、原版音乐音量及快速禁用原版背景音乐控件；方律音乐不接管 `MusicManager.currentMusic`，可与原版声音并行播放。
- **修改文件**：
  - 修改 `src/client/java/com/cubiccadence/client/CubicCadenceClient.java`
  - 修改 `src/client/java/com/cubiccadence/client/config/ModConfig.java`
  - 修改 `src/client/java/com/cubiccadence/client/playback/AudioDecoder.java`
  - 修改 `src/client/java/com/cubiccadence/client/playback/AudioEngine.java`
  - 新增 `src/client/java/com/cubiccadence/client/playback/DecodedAudio.java`
  - 新增 `src/client/java/com/cubiccadence/client/playback/WaveAudioDecoder.java`
  - 新增 `src/client/java/com/cubiccadence/client/playback/LocalMusicSoundInstance.java`
  - 新增 `src/client/java/com/cubiccadence/client/mixin/CheckboxAccessor.java`
  - 新增 `src/client/java/com/cubiccadence/client/mixin/SoundManagerAccessor.java`
  - 新增 `src/client/java/com/cubiccadence/client/mixin/SoundEngineAccessor.java`
  - 新增 `src/client/java/com/cubiccadence/client/mixin/SoundBufferLibraryAccessor.java`
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 新增 `src/client/resources/cubic-cadence.client.mixins.json`
  - 新增 `src/client/resources/assets/cubic-cadence/audio/test-audio.wav`
  - 修改 `src/client/resources/assets/cubic-cadence/lang/zh_cn.json`
  - 修改 `src/client/resources/assets/cubic-cadence/lang/en_us.json`
  - 修改 `src/main/resources/fabric.mod.json`
  - 修改 `docs/design.md`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - 在受控单线程执行器中读取并将 WAV 解码为 16-bit 小端 PCM，通过 Mixin Accessor 注入原版 `SoundBufferLibrary`，由 Minecraft 自己的 Channel 与 OpenAL 设备输出；客户端退出时停止实例并关闭解码执行器；
  - `LocalMusicSoundInstance` 使用 `SoundSource.MASTER`、相对监听者和无距离衰减设置，最终音量为“Minecraft 主音量 × 方律音量”，不受原版 `MUSIC` 滑块影响；
  - 音乐界面提供播放/暂停/继续/停止按钮、播放状态和时长、方律独立音量滑块；关闭界面后音乐继续播放；
  - 增加“禁用原版背景音乐”复选框和原版音乐音量滑块：勾选只把原版 `MUSIC` 音量设为 0，不停止或替换 `MusicManager`；取消时恢复最近一次非零值，滑块与复选框双向同步并通过原版 `OptionInstance` 即时刷新；
  - 补充客户端 Mixin 配置、中英文文本和阶段 2 音频共存设计说明；当前本地测试音频约 3 秒，在线长音频和进度跳转留待流式解码阶段。

## 2026-08-19 04:15:25 - 新增功能（第一阶段基础框架重建）

- **变更概述**：工作区被还原至模板状态后，按 `docs/design.md` 第 9 节重新落地第一阶段 Fabric 项目骨架，并一并在落地时包含此前确认的三项 UI 修复，实现可编译、可运行、按 `M` 键打开/关闭音乐主界面的纯客户端 Mod，业务逻辑以 `TODO` 占位。
- **修改文件**：
  - 删除 `src/main/java/name/modid/` 与 `src/client/java/name/modid/` 整树（模板残留，含旧 main 入口与两个示例 Mixin）
  - 删除 `src/main/resources/cubic-cadence.mixins.json`、`src/client/resources/cubic-cadence.client.mixins.json`
  - 新增 `src/main/java/com/cubiccadence/model/` 下 10 个文件（5 个 record + 5 个 enum）
  - 新增 `src/main/java/com/cubiccadence/provider/` 下 6 个文件（MusicProvider 接口 + PageRequest/SearchPage/PlaylistPage record + SearchType/AudioQuality enum）
  - 新增 `src/main/java/com/cubiccadence/auth/` 下 2 个文件（AuthSession record + AuthState enum）
  - 新增 `src/client/java/com/cubiccadence/client/` 下 15 个文件（入口、config、auth、provider/netease、playback、sync、cache、ui/screen）
  - 修改 `gradle.properties`：`group` 由 `name.modid` 改为 `com.cubiccadence`
  - 修改 `src/main/resources/fabric.mod.json`：`environment` 改为 `client`，移除 `main` 入口与 Mixin 配置，`client` 入口改为 `com.cubiccadence.client.CubicCadenceClient`
- **变更内容**：
  - 采用 split source set + 纯客户端结构：`src/main` 承载 model/provider/auth 跨层契约，`src/client` 承载客户端实现；
  - 依据 Minecraft 26.2 反编译源码对齐 API：`Screen` 使用 `extractRenderState`/`extractBackground` 渲染、`isPauseScreen`/`onClose` 替代旧版 `shouldPause`/`close`，按键使用 `KeyMapping` + `KeyMappingHelper.registerKeyMapping`，界面切换使用 `Minecraft.setScreenAndShow`/`Gui.screen`；
  - `MusicLibraryScreen` 使用 `Screen` 默认背景（游戏画面模糊 + 半透明菜单遮罩），标题颜色使用 ARGB 不透明白 `0xFFFFFFFF`（`0xFFFFFF` 会因 alpha=0 被 `text()` 跳过），并在 `keyPressed(KeyEvent)` 中用 `openLibraryKey.matches(event)` 检测 `M` 键关闭界面；
  - 验证：`gradlew build` 成功，`compileJava` 与 `compileClientJava` 均通过，产物 `build/libs/cubic-cadence-1.0.0.jar` 包含客户端入口与全部契约类。

## 2026-08-19 03:11:16 - 新增功能（技术设计文档）

- **变更概述**：新增 `docs/design.md`，明确第一阶段 Fabric 项目骨架的包结构、领域模型、接口契约、模块职责、线程规则与 UI 素材方案。
- **修改文件**：
  - `docs/design.md`（新增）
  - `docs/minecraft-fabric-music-mod-development-document.md`（迁入）
  - `docs/netease-cloud-music-api-integration-requirements.md`（迁入）
  - `CHANGELOG.md`
- **变更内容**：
  - 将 `docs` 目录从工作区根目录迁入本工程；
  - 固定技术栈基线：Minecraft 26.2、Fabric Loader 0.19.3、Fabric API 0.158.0+26.2、Loom 1.17-SNAPSHOT、JDK 25、默认 Yarn 映射；
  - 采用 split source set + 纯客户端结构，`src/main` 承载 model/provider/auth 契约，`src/client` 承载客户端实现；
  - 逐类定义领域模型、Provider 接口、认证、播放、同步、缓存与 UI 类的字段和方法签名；
  - 确定 UI 直接引用 Minecraft 原版 `assets/minecraft/textures/gui/` 素材，不打包自定义贴图。

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
