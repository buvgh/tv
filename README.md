# 枫林晚TV

一个基于 Android 的聚合影视播放器项目，支持多源订阅、搜索、详情聚合、自动换线路、下载、历史记录、收藏和播放器增强功能。

## 下载 APK

- [枫林晚TV-v1.4.7-release.apk](./releases/%E6%9E%AB%E6%9E%97%E6%99%9ATV-v1.4.7-release.apk)

## 当前版本

- `versionName`: `1.4.7`
- `versionCode`: `47`

## 主要功能

- 多订阅源导入与定期更新
- 首页推荐与全局搜索
- 收藏、历史、下载页
- 播放失败自动切换线路
- 手动横竖屏切换
- 小窗播放
- 后台下载与断点续传
- 成人内容过滤

## 开发说明

项目使用 Android Studio / Gradle 构建。

本仓库不包含发布签名密钥。`release` 签名配置通过本地环境变量或 `local.properties` 注入：

- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
