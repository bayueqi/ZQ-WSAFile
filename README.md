<div align="center">
  <img src="assets/icon.png" width="128" alt="ZQ-WSAFile"/>

  # ZQ-WSAFile

  **导出已安装应用的 APK，并内置浏览器文件管理**
</div>

---

## 功能

- 列出已安装应用，导出基础 APK 与全部分片，可打包为 `.apkm` 或 `.zip`
- 显示应用版本、大小、安装与更新时间、权限、签名、清单组件
- 自动备份新安装或更新过的应用
- 内置 HTTP 文件服务：在浏览器里浏览、下载、上传、删除共享存储中的文件
- 界面为简体中文

## 安装

从 Actions 构建产物或 Releases 下载 `ZQ-WSAFile-debug.apk`，安装即可。

要求 Android 13（API 33）及以上。

## 使用

**导出 APK**：主页列出所有应用，点击进入详情，选择保存目录后导出；也可以多选批量保存。

**文件管理**：主页右上角列表图标进入。授予「所有文件访问权限」后打开开关，服务地址显示为 `http://127.0.0.1:8899`。在电脑浏览器打开该地址即可管理文件：

- 点击目录进入，点击文件名下载
- 顶部「上传文件」可多选上传到当前目录
- 每行右侧「删除」按钮移除文件或整个目录

Android 子系统中运行时，电脑与 Android 共用 `127.0.0.1`，地址直接可用。

## 编译

仓库已配置 GitHub Actions，推送到 `main` 分支即自动构建，产物为 `ZQ-WSAFile-debug`。

本地构建：

```
./gradlew assembleDebug
```

## 许可

基于 [APKs Exporter](https://github.com/ryosoftware/apks-exporter) 修改，遵循原项目的 CC BY-NC-SA 4.0 许可，详见 [LICENSE.md](LICENSE.md)。
