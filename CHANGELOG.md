# Changelog

## 2026-08-20 00:37:42 - 修复问题（暂停失效、二维码返回主页入口与退出二次确认）

- **变更概述**：修复本地音乐暂停后返回游戏仍继续播放的问题；为二维码登录页新增「返回主页」按钮；退出登录增加二次确认。
- **修改文件**：
  - `src/client/java/com/cubiccadence/client/playback/AudioEngine.java`
  - `src/client/java/com/cubiccadence/client/ui/screen/LoginQrScreen.java`
  - `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - `src/client/resources/assets/cubic-cadence/lang/zh_cn.json`、`en_us.json`
- **变更内容**：
  - `AudioEngine.tick()`：当 `state == PAUSED` 且当前 channel 存在时，每帧重新执行 `handle.execute(Channel::pause)`。`Channel.pause()` 内部只在 `AL_PLAYING` 时真正暂停、已是 `PAUSED` 时为 no-op，因此能在一帧内纠正因异步竞态或 SoundEngine 全局恢复导致的「状态显示暂停但底层仍在播放」，同时不影响正常恢复；
  - `LoginQrScreen`：新增「返回主页」按钮（`button.cubic-cadence.back`），与「重新获取二维码」并排，点击复用 `returnToLibrary()`；关闭（ESC）仍走 `returnToLibrary()`，实现等待授权时随时回主页继续等待；
  - `MusicLibraryScreen`：`SIGNED_IN` 时点击登录按钮改为弹出原版 `ConfirmScreen` 二次确认，确认后才 `audioEngine.stop()` 并 `logout()`；确认或取消后均回到音乐库主页；
  - 语言文件：新增 `button.cubic-cadence.back` 与 `confirm.cubic-cadence.logout_title/message/confirm/cancel`。
- **风险**：`tick()` 在 `PAUSED` 期间每帧提交一个异步 `pause` Consumer，开销极小；`Channel.pause()` 的 `PLAYING` 检查保证不会误伤正常恢复。返回主页与 ESC 共用 `returnToLibrary()`，靠 `navigated` 防重复跳转。
- **验证**：`compileJava/compileClientJava/compileTestJava/test` 全部通过；中英文语言 JSON 校验通过。暂停与退出确认的真实游戏内交互需运行游戏实例人工确认。

## 2026-08-19 23:43:18 - 修复问题（登录体验优化：等待反馈、速度与返回主页）

- **变更概述**：优化网易云二维码登录体验。将扫码后轮询间隔从 2 秒降到 1 秒、缩短请求/连接超时；暴露「已扫码待确认」状态并在主页/二维码页显示；二维码垂直居中并加生成动画；移除不能独立完成登录的「在浏览器打开」按钮；授权成功或关闭二维码页时返回「音乐库」主页继续等待。
- **修改文件**：
  - `src/client/java/com/cubiccadence/client/provider/netease/NeteaseAuthClient.java`
  - `src/client/java/com/cubiccadence/client/auth/AuthManager.java`
  - `src/client/java/com/cubiccadence/client/ui/screen/LoginQrScreen.java`
  - `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - `src/client/resources/assets/cubic-cadence/lang/zh_cn.json`、`en_us.json`
  - `src/test/java/com/cubiccadence/client/auth/AuthManagerTest.java`
- **变更内容**：
  - `NeteaseAuthClient`：`POLL_INTERVAL_MS` 2s→1s（对齐 `AuthManager` 的 `MIN_POLL_INTERVAL_MS`），`REQUEST_TIMEOUT` 15s→10s，`connectTimeout` 10s→5s，扫码后更快感知结果、服务不可达时更快暴露错误；
  - `AuthManager`：新增 `volatile lastStatus` 与 `getLastStatus()`，在 `beginLogin`/授权成功/拒绝/过期/错误时重置为 `PENDING`，轮询返回 `SCANNED` 时记录并保持 `AUTHORIZING`，供 UI 区分「等待扫码」与「已扫码待确认」；
  - `LoginQrScreen`：移除「在浏览器打开」按钮（`authorizationUrl` 与二维码内容相同，无法独立登录）；二维码由固定 `QR_TOP=54` 改为垂直居中；二维码生成中按 tick 渲染省略号动画；新增 `SCANNED` 黄色提示；授权成功与手动关闭均通过 `returnToLibrary()` 返回 `MusicLibraryScreen`，并用 `navigated` 防重复跳转；
  - `MusicLibraryScreen`：`AUTHORIZING` 时顶部显示等待原因（需手机 App 扫码确认），登录按钮由置灰改为「查看二维码」可重新打开二维码页，`SCANNED` 时同步显示「已扫码待确认」；仅 `REFRESHING` 状态保持按钮置灰；
  - 语言文件：移除 `button.cubic-cadence.open_browser` 与 `button.cubic-cadence.authorizing`，新增 `button.cubic-cadence.view_qr`、`auth.cubic-cadence.authorizing_home`、`auth.cubic-cadence.scanned`；
  - 测试：新增 `exposesScannedStatusWithoutCompletingAuthorization` 用例，验证 `SCANNED` 保持 `AUTHORIZING` 且 `getLastStatus()` 正确暴露。
- **风险**：轮询降到 1 秒会轻微增加自建 `api-enhanced` 服务的轮询频率（二维码有效期 5 分钟，可接受）；超时下调可能使网络抖动时的单次慢请求更快判定失败，但会通过错误文案与「重新获取二维码」按钮可恢复。
- **验证**：`compileJava/compileClientJava/compileTestJava/test` 全部通过；中英文语言 JSON 校验通过。

## 2026-08-19 22:43:46 - 重构（客户端直连 api-enhanced，废弃 Spring Boot 后端）

- **变更概述**：按已确认的方案 A，废弃 Spring Boot 后端与官方适配器，Mod 直连自建的 `api-enhanced` 服务；鉴权模型从 accessToken/refreshToken 改为网易云 Cookie，二维码登录改为 api-enhanced 的 `/login/qr/key`、`/login/qr/create`、`/login/qr/check` 三步。
- **修改文件**：
  - 删除 `backend/` 整个目录、`docs/auth-gateway-openapi.yaml`、`docs/demo.zip`；
  - 修改 `src/main/java/com/cubiccadence/auth/AuthSession.java`（accessToken/refreshToken → cookie）；
  - 重写 `src/client/java/com/cubiccadence/client/provider/netease/NeteaseAuthClient.java`（直连 api-enhanced 二维码登录、刷新、退出）；
  - 修改 `src/client/java/com/cubiccadence/client/config/ModConfig.java`（authGatewayBaseUrl → apiEnhancedBaseUrl）、`CubicCadenceClient.java`、`NeteaseMusicProvider.java`、`UnavailableMusicProvider.java`；
  - 修改 `src/test/.../AuthManagerTest.java`、`WindowsDpapiTokenStoreTest.java` 适配 Cookie 模型；
  - 修改 `README.md`、`docs/design.md`、`docs/minecraft-fabric-music-mod-development-document.md`、`docs/netease-cloud-music-api-integration-requirements.md`；
  - 修改 `CHANGELOG.md`。
- **变更内容**：
  - `AuthSession` 只保留 `providerId`、`cookie`、`expiresAtEpochMs`；`expiresAtEpochMs` 在登录/刷新成功时填保守的 7 天默认值用于触发定时刷新；
  - `NeteaseAuthClient` 直连 api-enhanced：`/login/qr/key` 取 unikey、`/login/qr/create` 取二维码 URL、`/login/qr/check` 轮询（`code` 801 等待 / 802 已扫码 / 803 成功并返回 Cookie / 800 过期）、`/login/refresh` 用 Cookie 续期、`/logout` 退出；
  - 客户端通过 `apiEnhancedBaseUrl` 配置 api-enhanced 服务地址，不再持有开发者私钥或官方令牌；
  - 保留 `NeteaseApiClient` 为空实现，业务端点（搜索/歌单/播放）留待阶段 4。
- **风险**：api-enhanced 为逆向第三方服务，需自行部署并常驻运行；接口可能随上游变化。Cookie 无权威过期时间，7 天为保守默认值，实际以 api-enhanced 返回为准。
- **验证**：客户端 `compileJava/compileClientJava/compileTestJava/test` 通过；后端因已废弃不再参与构建。

## 2026-08-19 21:08:39 - 变更决策（网易云数据源改为第三方逆向库）

- **变更概述**：因网易云官方对个人开发者不支持多用户 Open API（仅开放 `ncm-cli`，厂商接入需联系商务），将数据源从官方多用户 API 改为第三方逆向库 `NeteaseCloudMusicApiEnhanced/api-enhanced`，并同步更新相关文档。
- **修改文件**：
  - `docs/netease-cloud-music-api-integration-requirements.md`
  - `docs/minecraft-fabric-music-mod-development-document.md`
  - `docs/design.md`
  - `README.md`
  - `CHANGELOG.md`
- **变更内容**：
  - 在接口需求文档中移除「正式编码前不得根据非官方逆向接口补全端点」「不会使用 Binaryify、NeteaseCloudMusicApi Enhanced 或其他逆向接口」的旧边界，改为明确记录采用 `api-enhanced` 作为数据来源；
  - 新增风险提示：该库逆向网易云未公开私有接口，非官方授权，公开发布存在账号封禁、服务条款与法律合规风险，接口无稳定性承诺、可能随时失效；
  - 同步更新开发文档、设计文档和 README 中的接入方式与阶段路线描述；
  - 本次仅改文档，代码从官方适配器切换到 `api-enhanced` 的落地工作尚未执行，后续单独规划。
- **风险与边界**：本变更属团队在「个人开发者无法接入官方多用户 API」前提下的知情决策，不应对外描述为网易云官方认可的实现；公开发布前需重新评估合规风险。

## 2026-08-19 20:15:01 - 新增功能（网易云官方适配器与游戏内登录二维码）

- **变更概述**：按网易云开放平台官方 demo 与 API 文档，实现真实后端认证适配器（RSA-SHA256 签名、匿名登录、二维码获取、状态轮询、令牌刷新），并在游戏内新增独立登录二维码弹窗，玩家无需离开游戏即可扫码授权。
- **修改文件**：
  - 后端新增 `NeteaseSigner.java`、`NeteaseOpenApiClient.java`、`ConfiguredNeteaseOfficialAuthAdapter.java`、`NeteaseAuthAdapterConfiguration.java` 及 `NeteaseSignerTest.java`；
  - 后端修改 `NeteaseOfficialAuthAdapter.java`、`UnconfiguredNeteaseOfficialAuthAdapter.java`、`AuthGatewayService.java`、`AuthGatewayController.java`、`RefreshSessionRequest.java`、`application.yml`、`.env.example`、`README.md`；
  - 客户端新增 `LoginQrScreen.java`；修改 `build.gradle`、`NeteaseAuthClient.java`、`MusicLibraryScreen.java` 及中英文语言文件；
  - 修改 `docs/auth-gateway-openapi.yaml`。
- **变更内容**：
  - 按官方 demo 实现 RSA-SHA256 签名：除 `sign` 外的参数按 key 字典序拼接，用 PKCS#8 私钥做 `SHA256withRSA` 签名并 Base64，作为 `sign`；GET 请求中 `device`、`bizContent`、`sign` 额外做一次 URL 编码；
  - 实现四个官方端点：匿名登录 `/openapi/music/basic/oauth2/login/anonymous`、获取二维码 `/openapi/music/basic/user/oauth2/qrcodekey/get/v2`、轮询 `/openapi/music/basic/oauth2/device/login/qrcode/get`、刷新 `/openapi/music/basic/user/oauth2/token/refresh/v2`；
  - 轮询状态 `800/801/802/803/804` 映射到 `EXPIRED/PENDING/SCANNED/AUTHORIZED/EXPIRED`，并保留 `1406/1407/1408` 令牌异常语义说明；
  - 仅当 `NCM_APP_ID`、`NCM_APP_SECRET`、`NCM_PRIVATE_KEY` 全部注入时才启用真实适配器，否则仍 fail-closed（`ready=false`、返回 HTTP 503）；
  - `refresh` 契约新增 `accessToken` 字段，因为网易云刷新接口需要旧 accessToken 参与；
  - 客户端引入 ZXing core，新增 `LoginQrScreen` 独立弹窗：游戏内渲染授权二维码、实时显示授权状态、授权成功自动关闭，并保留“在浏览器打开”和“重新获取二维码”按钮；
  - 登录动作由跳转系统浏览器改为打开游戏内二维码弹窗。
- **验证**：后端 `test` 通过（含签名“签名→验签”自检、PEM 私钥解析、字典序拼接）；客户端 `compileJava/compileClientJava/test` 通过。真实扫码联调需真实凭据，本轮未执行。

## 2026-08-19 18:55:00 - 新增功能（阶段 3 登录客户端与公开发布后端边界）

- **变更概述**：实现 Mod 侧无密码浏览器授权状态机、Windows DPAPI 会话持久化、自动刷新与退出清理，并新增独立认证网关工程和 OpenAPI 契约；由于当前没有可审查的网易云官方服务端接入文档，网关的官方适配器保持 fail-closed，未伪造私有接口或把 `privateKey` 放进 Mod。
- **修改文件**：
  - 修改 `.gitignore`、`README.md`、`build.gradle`、`docs/design.md`、`docs/minecraft-fabric-music-mod-development-document.md`、`docs/netease-cloud-music-api-integration-requirements.md`；新增 `docs/auth-gateway-openapi.yaml`；
  - 修改 `src/main/java/com/cubiccadence/auth/AuthSession.java`、`src/main/java/com/cubiccadence/provider/MusicProvider.java`；新增 `AuthorizationChallenge.java`、`AuthorizationResult.java`、`AuthorizationStatus.java`；
  - 修改 `src/client/java/com/cubiccadence/client/CubicCadenceClient.java`、`auth/AuthManager.java`、`config/ModConfig.java`、`provider/netease/NeteaseAuthClient.java`、`provider/netease/NeteaseMusicProvider.java`、`ui/screen/MusicLibraryScreen.java` 及中英文语言文件；新增 `auth/WindowsDpapiTokenStore.java`、`provider/UnavailableMusicProvider.java`；
  - 新增 `src/test/` 下 AuthManager 与 DPAPI 令牌存储测试；新增独立 `backend/` Spring Boot 认证网关工程、测试、容器配置与部署说明；修改 `CHANGELOG.md`。
- **变更内容**：
  - `AuthManager` 实现 `SIGNED_OUT/AUTHORIZING/SIGNED_IN/REFRESHING/EXPIRED/ERROR` 状态流转、授权轮询、启动恢复、到期前五分钟自动刷新、并发操作失效保护及退出本地清理；登录界面显示状态并使用系统浏览器打开 HTTPS 授权页，用户不向 Mod 输入或提供网易云密码；
  - Mod 仅保存项目网关签发的短期访问令牌和轮换刷新令牌，并使用当前 Windows 用户的 DPAPI 加密后原子写入配置目录；网关地址配置不含密钥，非本机 HTTP 地址被拒绝，响应大小、超时和重定向均受限制；
  - `backend/` 定义授权创建/轮询、会话刷新/撤销和 readiness 边界，`appId/privateKey` 仅允许由后端环境变量注入；未配置经审查的官方适配器时所有认证入口返回不可用，防止公开版本误用逆向私有协议；
  - 验证根工程与后端测试通过，覆盖登录持久化、有效会话恢复、临期刷新、远端撤销失败时的本地退出以及 DPAPI 密文不含明文令牌。

## 2026-08-19 17:38:25 - 新增功能（配置持久化与音量/格式记忆）

- **变更概述**：将方律音量和本地测试音频格式选项持久化到 `config/cubic-cadence.json`，使退出游戏重进后音量保持不变，退出音乐菜单再进入后格式选项保持上次选择。
- **修改文件**：
  - 修改 `src/client/java/com/cubiccadence/client/config/ModConfig.java`
  - 修改 `src/client/java/com/cubiccadence/client/CubicCadenceClient.java`
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - `ModConfig` 由内存单例扩展为 JSON 持久化配置，使用 Gson 读写 Fabric 配置目录下的 `cubic-cadence.json`，字段包含 `volume`、`hudEnabled`、`audioQuality` 与 `lastTestTrackIndex`；读取时对缺失文件、JSON 损坏、非法枚举值做兜底并回退默认值，不影响游戏启动；
  - 音量与格式选项在变更时立即保存，并在客户端退出时兜底保存；`MusicLibraryScreen` 构造时从配置恢复格式选项，切换格式时写回配置，退出菜单再进入仍保持上次选择；

## 2026-08-19 17:12:37 - 优化代码（移除 M4A/AAC 音频支持）

- **变更概述**：按需求移除用不到的 M4A（含 MP4/AAC）音频支持，仅保留 WAV 与 MP3 解码能力，精简依赖与测试资源。

* **修改文件**：
  - 修改 `build.gradle`
  - 修改 `src/client/java/com/cubiccadence/client/playback/JavaSoundAudioDecoder.java`
  - 修改 `src/client/java/com/cubiccadence/client/CubicCadenceClient.java`
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 删除 `src/client/resources/assets/cubic-cadence/audio/test-audio.m4a`
  - 修改 `docs/design.md`
  - 修改 `CHANGELOG.md`
* **变更内容**：
  - 从 `build.gradle` 移除 `javasound-aac` 与 `javasound-resloader` 的 implementation 与 include，仅保留 `javasound-mp3` 与 `tritonus-share`；
  - `JavaSoundAudioDecoder` 删除 AAC/MP4 解码分支、AAC 格式魔数识别、AAC 字段与字节序翻转逻辑，`supports()` 仅保留 wav/wave/mpeg/mp3；
  - 移除 `CubicCadenceClient.LOCAL_TEST_AUDIO_M4A` 常量与 M4A 测试资源，音乐界面测试格式切换仅保留 WAV/MP3 两项；
  - 同步更新 `docs/design.md` 的格式说明。

## 2026-08-19 14:59:51 - 修复问题（切换音频格式后仍播放旧格式）

- **变更概述**：修复切换 WAV/MP3/M4A 测试音轨后，实际播放内容仍为首次加载格式的问题；每次播放均用当前解码出的 PCM 重建 OpenAL 缓冲，并在替换或关闭时释放旧缓冲。
- **修改文件**：
  - 修改 `src/client/java/com/cubiccadence/client/playback/AudioEngine.java`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - `AudioEngine.installAndPlay(...)` 原逻辑仅在 `soundBuffer` 为空或失效时创建缓冲，导致首次加载后永久复用同一个 OpenAL 缓冲，切换格式后播放内容不变；改为每次都用当前 `decoded` 重新创建 `SoundBuffer`；
  - 替换旧缓冲前调用 `SoundBuffer.discardAlBuffer()` 释放其 OpenAL buffer，避免每次切换累积 OpenAL 缓冲泄漏；
  - `AudioEngine.close()` 同步补充 `discardAlBuffer()`，关闭引擎时释放仍持有的 OpenAL 缓冲，消除退出时的既有泄漏隐患。

## 2026-08-19 14:38:31 - 新增功能（MP3 与 AAC/MP4 音频解码支持）

- **变更概述**：为本地音乐播放链路新增 MP3 与 AAC（含 MP4/M4A 容器）解码能力，使模组除 WAV 外也能播放 MP3 与 MP4/M4A 音频文件；本地测试入口增加 WAV/MP3/M4A 三种格式切换。
- **修改文件**：
  - 修改 `build.gradle`
  - 新增 `src/client/java/com/cubiccadence/client/playback/JavaSoundAudioDecoder.java`
  - 删除 `src/client/java/com/cubiccadence/client/playback/WaveAudioDecoder.java`
  - 修改 `src/client/java/com/cubiccadence/client/CubicCadenceClient.java`
  - 修改 `src/client/java/com/cubiccadence/client/ui/screen/MusicLibraryScreen.java`
  - 新增 `src/client/resources/assets/cubic-cadence/audio/test-audio.mp3`
  - 新增 `src/client/resources/assets/cubic-cadence/audio/test-audio.m4a`
  - 修改 `docs/design.md`
  - 修改 `CHANGELOG.md`
- **变更内容**：
  - 引入 `com.tianscar.javasound:javasound-mp3:1.9.8`（LGPL 2.0）与 `com.tianscar.javasound:javasound-aac:0.9.8`（Apache 2.0）及传递依赖 `tritonus-share`、`javasound-resloader`，通过 Loom `include` 以嵌套 jar 形式打进 mod 包；
  - 新增 `JavaSoundAudioDecoder`，按文件魔数识别 WAV/MP3/MP4(AAC)，显式调用对应 JavaSound SPI provider 类完成解码，绕开 Fabric 嵌套 jar 下 `ServiceLoader` 无法稳定发现 SPI 的问题；
  - MP3 经 `MpegFormatConversionProvider` 解码为 16-bit 小端 PCM；AAC/MP4 经 JAAD 解码为 16-bit 大端 PCM 后统一翻转为小端，最终均归一化为 `PCM_SIGNED` 立体声/单声道格式交给 OpenAL；
  - 删除仅支持 WAV 的 `WaveAudioDecoder`，`AudioEngine` 改为使用通用解码器，原有 WAV 播放与进度/seek 逻辑不受影响；
  - 本地测试音乐界面增加格式切换按钮，可在 WAV、MP3、M4A 之间循环切换并播放对应测试资源，便于直接在游戏内验收三种格式。

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
