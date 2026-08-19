# Changelog

## 2026-08-19 13:56:51 - 优化代码（加长经验条与媒体图标按钮）

- **变更概述**：加长经验条进度控件，将纯时间数值放到条体正下方居中，并把播放/暂停切换和停止按钮的可见文字替换为常见媒体图标。
- **修改文件**：
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 修改 `docs/design.md`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - 经验条宽度由原版 182 像素加长为 220 像素，与原版音乐音量和方律音量控件同宽；灰色背景、绿色进度填充、像素拖动点及松开鼠标后单次 seek 的交互保持不变；
  - 经验条整体上移，纯时间数值以 `0:01 / 0:03` 的格式显示在条体正下方居中，并与播放按钮保留 6 像素间距；
  - 播放或继续使用 `▶`，播放中暂停使用 `Ⅱ`，停止使用 `■`，加载中使用 `…`；两个媒体按钮改为 40 像素宽并在进度区域下方居中；
  - 图标按钮通过 Minecraft `Tooltip` 保留“播放、暂停、继续、加载中、停止”的本地化文字说明，避免图标化后丢失操作语义。

## 2026-08-19 13:42:05 - 优化代码（进度拖动延迟提交与右侧时间布局）

- **变更概述**：将音乐进度拖动改为“拖动期间仅预览、释放鼠标后单次跳转”，避免连续 OpenAL seek 产生电音；同时将纯时间数值移动到经验条右侧。
- **修改文件**：
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 修改 `docs/design.md`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - 鼠标按下和移动期间只更新经验条绿色填充、像素拖动点与目标时间预览，不调用 `AudioEngine.seek(...)`，实际音频继续从原位置正常播放；
  - 鼠标释放时读取最终预览位置并只执行一次 seek，随后重新同步播放时钟和控件值；暂停状态下跳转后仍保持暂停；
  - 键盘方向键调整不属于连续鼠标拖动，继续保持每次按键提交一次跳转；
  - 时间由经验条上方移到右侧，与条体垂直对齐，仅显示 `0:01 / 0:03` 一类数值，不显示“进度”文字；经验条和时间按预留宽度作为整体居中。

## 2026-08-19 13:31:20 - 优化代码（经验条样式音乐进度控件）

- **变更概述**：将音乐进度控件由原版按钮式滑块改为 Minecraft HUD 经验条样式，复用原版灰色经验槽和绿色经验填充，并增加可拖动的像素菱形进度点。
- **修改文件**：
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 修改 `docs/design.md`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - 直接使用 Minecraft 26.2 内置 `hud/experience_bar_background` 与 `hud/experience_bar_progress` sprite，不新增或复制贴图资源；
  - 保持原版经验条 `182×5` 的视觉尺寸，根据播放比例裁切绿色填充，并在条体上叠加带深色描边的 `7×9` 像素菱形拖动点；
  - 拖动点在悬停、聚焦或拖动时变为亮绿色，禁用时变为灰色；控件保留更高的透明交互区域，改善细经验条的点击和拖动命中；
  - 将时间文本独立显示在经验条上方，播放中和暂停中的拖动跳转、键盘控制和无障碍朗读继续复用已有进度逻辑。

## 2026-08-19 13:23:13 - 新增功能（可拖动音乐进度条）

- **变更概述**：为本地测试音乐增加 Minecraft 原版样式进度条，播放中和暂停中均可拖动跳转，并保持界面时间、内部播放时钟与 OpenAL 实际声源位置同步。
- **修改文件**：
  - 修改 `src/client/java/com/cubiccadence/client/playback/AudioEngine.java`
  - 新增 `src/client/java/com/cubiccadence/client/mixin/ChannelAccessor.java`
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 修改 `src/client/resources/cubic-cadence.client.mixins.json`
  - 修改 `src/client/resources/assets/cubic-cadence/lang/zh_cn.json`
  - 修改 `src/client/resources/assets/cubic-cadence/lang/en_us.json`
  - 修改 `docs/design.md`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - `AudioEngine.seek(long)` 对跳转位置做边界限制，通过当前 `ChannelHandle` 的声音线程设置 OpenAL `AL_SEC_OFFSET`，暂停状态下跳转后仍保持暂停；
  - 新增 `ChannelAccessor`，仅暴露执行静态缓冲定位所需的 OpenAL source ID，并注册到客户端 Mixin 配置；
  - 音乐界面新增进度滑块，实时显示当前时间与总时长；播放或暂停时启用，未播放、加载中、结束或错误时禁用，拖动期间不会被每帧状态刷新覆盖；
  - 增加中英文进度条文本，并更新技术设计边界：当前静态 PCM/WAV 使用 OpenAL 定位，未来在线流式音频仍需由流式解码器单独实现 seek。

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
