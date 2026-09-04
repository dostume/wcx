---
name: wcx-build-windows
description: 在 Windows 上编译 wcx(微信 Xposed 模块) 项目。适用于需要在本机无 JDK/Android SDK 环境、项目路径含中文、子模块缺失的情况下跑通 ./gradlew assembleDebug 的场景。触发词：编译 wcx、gradlew assembleDebug、wcx 构建失败、Android 项目中文路径编译。
agent_created: true
---

# 在 Windows 上构建 wcx

wcx 是 Android/Kotlin 的 Xposed 模块（AGP 9.x + Gradle wrapper），本机默认没有 JDK、Android SDK，且项目路径含中文。

## 1. 必须镜像到纯 ASCII 路径

AGP 会直接拒绝非 ASCII 路径：`Your project path contains non-ASCII characters`（本机项目在 `D:\代码\...`）。
不要去改仓库里被 git 跟踪的 `gradle.properties`（加 `android.overridePathCheck=true` 只绕过检查，后续 aapt2 仍可能出问题），改用镜像目录：

```bash
mkdir -p /d/wcx_build
cd "D:/代码/workbuddy/wcx/repo"
git ls-files -z | tar --null -T - -cf - | (cd /d/wcx_build && tar xf -)
cp -r "D:/代码/workbuddy/wcx/repo/.git" /d/wcx_build/.git   # 构建脚本会跑 git rev-list --count HEAD
```
之后把工作区改动文件也 cp 到镜像里，在镜像里构建，最后把结果同步回原仓库提交。

## 2. 安装 JDK 21（构建主 JDK，bsh 需要源发行版 21）

```bash
curl -sL -o jdk21.zip "https://mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_windows-x64_bin.zip"
unzip -q jdk21.zip    # 约 190MB，华为镜像约 250KB/s，需 10~20 分钟
export JAVA_HOME="D:\wcx_build\jdk-21.0.2"; export PATH="$JAVA_HOME/bin:$PATH"
```
注意：`annotation-scanner` 通过 Gradle toolchain 指定 JDK 17，必须另备 JDK 17（同一镜像目录的 17.0.2 版本）。仓库 `gradle.properties`
里的 `org.gradle.java.installations.paths=/opt/jdk/jdk-17.0.2`（Linux 路径）在本机会被解析成
`<项目根>/opt/jdk/jdk-17.0.2`，用目录联接满足它（PowerShell）：

```powershell
New-Item -ItemType Junction -Path "D:\wcx_build\opt\jdk\jdk-17.0.2" -Target "D:\wcx_build\jdk-17.0.2"
```

## 3. Android SDK

```bash
curl -sL -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
mkdir -p android-sdk/cmdline-tools && unzip -q cmdtools.zip -d android-sdk/cmdline-tools
mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
echo y | ./android-sdk/cmdline-tools/latest/bin/sdkmanager.bat --licenses
yes | ./android-sdk/cmdline-tools/latest/bin/sdkmanager.bat "platforms;android-37.0" "platform-tools" "build-tools;37.0.0" "build-tools;36.0.0"
```
平台 37 在 sdkmanager 里的名字是 `platforms;android-37.0`（不是 `android-37`）；AGP 报 `Failed to find
Build Tools revision 36.0.0` 时补装该版本。写入 `local.properties`（该文件不要提交）：

```
sdk.dir=D\:\\wcx_build\\android-sdk
```

## 4. 子模块：仓库缺 .gitmodules，且指针是旧的

`libs/common/bsh`、`libs/common/reflekt` 在 git 里是 gitlink（160000），但仓库没有 `.gitmodules`，
本地目录为空。上游地址与可用版本：

- bsh：`https://github.com/Ujhhgtg/bsh`，**必须用 master（c7479f9）**，仓库指针 8a690126 是旧版，
  `LocalMethodHookParam` 缺 `interpreter`/`returnType`，会导致 `ScriptsDrmBypassHook` 编译失败
- reflekt：`https://github.com/Ujhhgtg/reflekt`，**用 master（3807ae1）**，指针 f46823e 缺
  `makeAccessible`/`spec`/`ReflectionClassLoader`

bsh 的 pinned commit 无法 clone 到，直接下载 tarball：

```bash
curl -sL "https://codeload.github.com/Ujhhgtg/bsh/tar.gz/<sha>" | tar xz --strip-components=1 -C libs/common/bsh
```
提交前可用 `git update-index --cacheinfo 160000,<完整sha>,libs/common/bsh` 更新子模块指针。

## 5. 仓库自带的编译错误（会阻塞 assembleDebug）

快照版本内部不同步，典型模式是「旧实现未删除、与新实现符号冲突」。判断口诀：
`Conflicting overloads` / `Overload resolution ambiguity` / lambda 参数 `Cannot infer type` 通常都是
两份文件重复定义顶层符号；`override ... overrides nothing` 是被 override 的接口已改版。
修好后才能在镜像里 `./gradlew assembleDebug`（产出 legacy 与 standard 两个 flavor）。

基线 `3fae073` 实测共 5 组，修法如下（先跑
`./gradlew --no-daemon :app:compileLegacyDebugKotlin | grep "^e: "` 拿全量清单，
legacy 编译通过即代表 standard 也能过，可省一半时间）：

| 组 | 症状 | 修法 |
|---|---|---|
| net | `ISigner`/`IPacketPreprocessor` Redeclaration | 删 `features/api/net/WePacketPreprocessors.kt`（旧实现，已被 `WePacketSigner.kt` 取代，先 grep 确认无引用） |
| protobuf | `OpBufProto` Redeclaration | 删 `models/protobuf/SKBuiltinProto.kt` 末尾的 `typealias OpBufProto`，保留 `OpLogProto.kt` 里的 data class |
| Automation | 大量 Redeclaration + payment 组连锁 `IWeContact` 报错 | 删 `features/items/AutomationSettings.kt`（已被 `AutomationCore.kt` + `payment/PaymentSettingsUi.kt` 取代，其 5 个 `*Ui` 组件已无引用） |
| loader | `overrides nothing` | 见下 |
| 资源 | `Unresolved reference 'res_inject_success'` | 在 `res/values/strings.xml` 补该字符串（哨兵资源，用于探测模块资源是否注入成功） |

loader 组四处（接口 `IHookBridge`/`ILoaderService` 已改版）：
- `ArtHookBridge`：`hookBridgeName` 接口里没有 → 换成 `override val apiLevel`（值随意，全项目无人读）；
  `deoptimize(executable: Executable)` → `deoptimize(member: Member)`
- `ZygiskLoaderService`：删掉接口里没有的 `loaderName`
- `ZygiskEntry`：删残留的 `NativeLoader.configureZygiskPayload(apkPath, dataDir)`（NativeLoader 只有 `init`）；
  `val started = try { ModuleLoader.init(...) }`——`init` 返回 `Unit`，与 catch 的 `false` 合并成 `Any`，
  `if (started)` 会报 `Condition type mismatch`，要在 try 末尾补 `true`
- `ResourcesInjector`：`StartupInfo.modulePath` 不存在 → 改成 `StartupInfo.loaderService.mainModulePath`

## 5.1 陷阱：`mergeLegacyDebugJavaResource` 报「zip-cache … 拒绝访问」

Kotlin 全部编译通过后才可能炸在打包阶段，是 Windows 文件句柄占用，**不是代码问题**：

```
> java.io.FileNotFoundException: ...\app\build\intermediates\incremental\
  legacyDebug-mergeJavaRes\zip-cache\xxxx== (拒绝访问。)
```

删掉增量缓存目录重跑即可，不要去 clean 整个 build（会多花 10 分钟重编）：

```bash
rm -rf app/build/intermediates/incremental/*-mergeJavaRes
```

## 6. 陷阱：`Process 'command 'git'' finished with non-zero exit value 1`

`app/build.gradle.kts` 顶部用 `providers.exec { commandLine("git","rev-list","--count","HEAD") }` 算
versionCode，配置期执行。症状：**同一命令在 bash / PowerShell 里都正常（返回 111），只有 Gradle 调用时
退出码 1 且 stdout 为空**，报错定位在 `app/build.gradle.kts` 第 18 行。

排查时不要去改 git 命令本身（曾试过 `cmd /c` 包装、`errorOutput` —— 后者在该 Gradle 版本不存在，
会报 `Unresolved reference 'errorOutput'`）。

**根因是 Gradle 守护进程状态失效**，与 git 无关：

```bash
./gradlew --stop          # 之后重新构建即可恢复正常
```

若守护进程重启后仍失败，再考虑环境（PATH 里 `git` 需解析到 `C:\Program Files\Git\cmd\git.exe`）。

## 7. 常用核查

- `@Feature` 注解由 KSP 自动注册，新功能文件无需手动登记；可查
  `app/build/generated/ksp/<variant>/kotlin/com/Johnny/wcx/features/core/FeaturesProvider.kt` 确认已扫描到
- **`@Feature` 的 name 全仓必须唯一**：DEX 缓存文件按功能显示名命名（`dex_cache/<name>.json`），
  重名功能写同一缓存 → 其中一方哈希校验永远失败 → 每次启动弹「N 个功能需要更新」且适配不收敛。
  排查入口：解包用户导出的 dex_cache.zip，比对文件名集合 vs 源码功能名集合；再查 @Feature 重名
- 新增长按菜单项前先 grep `7770\d\d`。菜单 id **按菜单 API 隔离唯一**即可——共 5 个编号空间：
  `WeChatMessageContextMenuApi`(消息长按, 合并入口 777000)、`WeConversationContextMenuApi`(会话长按)、
  `WeMomentsContextMenuApi`(朋友圈)、`WeHomeScreenPopupMenuApi`(首页弹窗)、`WeShortVideosShareMenuApi`(视频号分享)。
  跨菜单同数字不算冲突（如 777010 同时被消息菜单与首页弹窗使用是合法的）
- **多选 msgInfos 是残缺对象**：`MultiSelectSupport.Adapted` 收到的消息取自微信多选
  处理器的 List 字段，msgSvrId/msgId/imgPath 等字段常缺失（单选的 view tag 才是完整
  MsgInfo）。消费前必须 `normalizeMsgInfo` 重建权威对象——参见 `ScheduledMessageMenu`
  + `WeMessageApi.getMsgInfoInstanceByMsgId`（msgId 查库经 convertFrom 重建）、
  `findMsgIdByAttributes`（属性匹配兜底）、`resolveExistingImageByPath`（image2 已落地
  大图兜底）。直接读字段会在单选正常、多选必败
- **媒体发送优先走"复读/转发"通道，别自己等 CDN**：`ForwardMessages` 的发送路径
  （图片 `getImageMd5FromMsgInfo`→`sendImageByMd5`、语音 `getVoiceFullPath`+`AudioUtils.getDurationMs`→
  `sendVoice`、视频 `getVideoMp4PathFromMsgInfo`→`sendVideo`）在发送时刻从微信本地缓存解析媒体，
  从不依赖 CDN 下载。定时发送（ScheduledMessage）据此定型为"引用段"模型：
  创建=瞬时本地检查（文件在磁盘→备份 moduleCache；不在→记 srcTalker/srcMsgId/srcSvrId 引用），
  发送=按引用重建 MsgInfo→走复读通道→CDN 兜底。创建路径保持零下载零等待。
  注意：`AudioUtils.getDurationMs` 是 external(JNI) 函数，文件不存在时行为不可控，调用前必须先查文件落盘；
  文件下载可用 `resolveExistingFilePath`（纯查 appattach 表瞬时返回）做无阻塞检查
- 只改 `.kt` 正本，勿改同目录下的 `.bak_vXXX` 备份文件
- **Dex 缓存不会被新功能打断**：`buildSrc/.../GenerateMethodHashesTask.java` 只对
  `override fun resolveDex(` 的**方法体**做 MD5，写进
  `app/build/generated/source/methodhashes/.../GeneratedMethodHashes.kt`（当前 135 条）。
  新增功能/改菜单/改 UI 都不会改变任何哈希 → 用户手机上已有的 dex 缓存保持有效。
  只有增删 `IResolveDex` 实现类或改 `resolveDex()` 才会使条目数/哈希变化
  （属正常变更，用户适配一次即收敛；但若适配后反复弹窗，先查 @Feature 重名）

## 7.1 陷阱：沙箱里 `git clone` 的写盘是虚拟化的

在 CodeBuddy 的 bash 里 `git clone` 会报成功、`git log` 也能看到提交，但**真实磁盘上目录不存在**
（`ls` 与 PowerShell 都看不到），后续构建全部落空。curl / mkdir / unzip / `git init` / `git commit`
不受影响，只有 `git clone` 的写盘被重定向。

绕过办法：clone 到影子目录，用 `git archive` 让 **tar**（非 git 进程）把文件解到真实磁盘，
再用 bundle 把历史喂给本地 `git init` 的仓库：

```bash
git clone --quiet <url> /d/wcx_build/shadow-repo          # 内容在影子层
mkdir -p /d/wcx_build/wcx-src
git -C /d/wcx_build/shadow-repo archive --format=tar HEAD | tar -x -C /d/wcx_build/wcx-src
git -C /d/wcx_build/shadow-repo bundle create - HEAD > /d/wcx_build/wcx.bundle
cd /d/wcx_build/wcx-src && git init -q -b master && git fetch ../wcx.bundle HEAD && git reset -q --mixed FETCH_HEAD
```

子模块同理（`git -C shadow-bsh archive --format=tar <sha> | tar -x -C libs/common/bsh`）。
历史恢复后用 `git rev-list --count HEAD` 核对（基线 111），`build.gradle.kts` 靠它算 versionCode。

## 8. Release 构建（R8 缩减 → 体积接近原安装包）

`assembleDebug` 默认 `isMinifyEnabled=false`、`isShrinkResources=false`，所以不缩减、dex 不压缩，
产物 ~124MB（30 个 dex）。`release` 构建类型默认 `isMinifyEnabled=true` + `isShrinkResources=true`，
经 R8 把 Kotlin/Compose/DexKit/YukiHook 等依赖的未用代码裁掉并混淆，dex 收敛到 3 个，APK ~40MB（约 1/3）。

```bash
cd /d/wcx_build
export JAVA_HOME=/d/wcx_build/jdk-21.0.2; export PATH=$JAVA_HOME/bin:$PATH
export GRADLE_OPTS="-Xmx4g -XX:MaxMetaspaceSize=1g"
export ANDROID_SDK_ROOT=/d/wcx_build/android-sdk
./gradlew --no-daemon assembleRelease
```

**keystore**：`release` 签名配置只有在找到 keystore 时才创建（否则 release 无签名配置会直接失败）。
默认搜索顺序：`$HOME/wcx-keystore.jks` → `$HOME/.wcx/wcx-keystore.jks`（alias=`wcx`，
store/key 密码=`wcx-store-pass`）。本机没有时用默认凭据生成：

```bash
mkdir -p /c/Users/Administrator/.wcx
MSYS_NO_PATHCONV=1 /d/wcx_build/opt/jdk/jdk-17.0.2/bin/keytool.exe -genkeypair -v \
  -keystore "C:\Users\Administrator\.wcx\wcx-keystore.jks" -alias wcx -keyalg RSA \
  -keysize 2048 -validity 10000 -storepass wcx-store-pass -keypass wcx-store-pass -dname "CN=wcx"
```
注意：Git Bash 会把 `/c/...` 转成非法的 `\c\...`，必须 `MSYS_NO_PATHCONV=1` + 传 Windows 原生路径。
（此 keystore 仅供本地构建；对外发布仍需你自有的正式 keystore，体积与用哪个 keystore 无关。）

**陷阱：`lintVital` 把 release 拦挂**（镜像基线 `3fae073` 资源翻译不完整，3416 个 `ExtraTranslation` 类错误）。
lint 是发布版质量门，与体积无关，但默认 `abortOnError=true` 会让构建直接 BUILD FAILED。
在镜像副本的 `app/build.gradle.kts` 的 `android {}` 内加（**只改镜像，勿提交真仓库**）：

```kotlin
lint {
    abortOnError = false
    checkReleaseBuilds = false
}
```
关掉 lint 只为了让构建跑完，**不改变产物大小**。功能验证应搜中文字符串常量（如 `定时发送消息`），
而非类名——R8 会把类名混淆成短名，按原类名 grep 会误判为缺失。

**变体差异**：`assembleRelease` 会产出 arm64-v8a **和** armeabi-v7a（standard/legacy 共 4 个 APK）；
`assembleDebug` 只产 arm64（2 个）。现代设备用 `app-standard-arm64-v8a-release.apk`。

**ProGuard 瘦身实测（2026-09）**：40.2MB → **25.4MB**（arm64），仅改 `app/proguard-rules.pro`：
- 删 `-keep class androidx.compose.** { *; }`（省 ~11MB dex；Compose 无字符串反射/清单引用，摇树安全）
- 删 `-keep,allowobfuscation class com.Johnny.wcx.features/hooks/datas.** { *; }`（省 ~3.4MB；
  功能类全由 KSP 生成的 FeaturesProvider **直接引用**注册，全仓 `Class.forName` 指向这些包为 0，
  R8 只重命名不误删，被裁掉的是包内死类）
- 删 `-keepattributes SourceFile,LineNumberTable` 无收益（0MB），勿浪费时间
- kotlin.reflect 的整体 keep 有 `IllegalStateException` 风险注释，勿轻易裁
- 改后验证法：解包 APK grep 中文字符串常量（`定时发送` 等）+ 核对 GeneratedMethodHashes 条目数不变

## 云端构建（GitHub Actions，2026-09 接入）

仓库 `https://github.com/dostume/wcx` 已配置 `.github/workflows/build-release.yml`：

- **触发**：push 到 `master`/`main` 自动构建；打 `v*` tag 额外发布 GitHub Release 并附 4 个 APK；
  手动触发用 workflow_dispatch
- **环境**：`ubuntu-latest` + Temurin JDK 21 + `gradle/actions/setup-gradle@v4`；
  `checkout` 必须 `fetch-depth: 0`（build.gradle.kts 用 git rev-parse 生成 COMMIT_HASH）
- **签名**：仓库 Secret `WEKIT_KEYSTORE_BASE64` 存有本机 `~/.wcx/wcx-keystore.jks` 的 base64，
  workflow 里解码到 `$HOME/wcx-keystore.jks`（build.gradle 默认搜索路径之一），密码走
  build.gradle 里的默认值 `wcx-store-pass`/alias `wcx`，产出与本地**完全相同签名**的 APK，
  可直接覆盖安装；删掉 Secret 则自动回退 debug 签名
- **产物位置**：Actions 运行页的 Artifacts（`wcx-apks-<sha>`，4 个 APK）；tag 构建在 Releases 页
- **注意**：本机 shell 环境变量代理可能失效（CONNECT 502），API 调用要显式
  `--proxy http://127.0.0.1:10809`（git 全局配置的那个）；创建仓库/上传 Secret 用
  git credential fill 取 token + GitHub API（Secret 需 PyNaCl sealed box 加密）
