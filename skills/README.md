# Skills

本目录存放与本项目构建/维护相关的可复用技能文档。

- [wcx-build-windows](./wcx-build-windows/SKILL.md) — 在 Windows 本机（无 Android Studio、
  含中文路径规避）从零编译出 Release APK 的完整流程：JDK/SDK 影子仓库、镜像仓库、
  编译命令、Dex 缓存机制与常见坑。

GitHub Actions 云端构建见 [.github/workflows/build-release.yml](../../.github/workflows/build-release.yml)：
push 到 master/main 或打 `v*` tag 时自动产出 4 个 Release APK
（standard/legacy × arm64-v8a/armeabi-v7a），tag 推送会额外发布 GitHub Release。
