# Flue-compose-mod


Flue-compose 增强修改版。在原版基础上新增以下功能（本作大量使用vibecoding代码不美观请见谅）：

加入了不完整的jbwatch表盘支持，内置下载功能





- **手势自定义** — 设置页可交换上滑/左滑手势，四种方向独立配置
- **蜂窝边缘快滑** — 右侧透明区域滑动触发快速滚动，宽度/倍率可调
- **小组件自动恢复** — 重启后自动重绑失效的 widget，无需手动删加重加
- **Lyricon 歌词集成** — 控制中心歌手名替换为实时歌词显示
- **蜂窝左滑返回** — 左侧滑返回表盘，右侧滑触发边缘快滑
- **控制中心布局优化** — 电量图标内嵌表盘设置入口
- **性能优化** — 图标缓存提升、低端设备自动降级、减少动画与重绘

## JB 表盘修复

JB 表盘渲染正在修复中，参考项目为 [watchmaker-definition](https://github.com/avnpadman/watchmaker-definition)（WatchMaker 格式定义、Lua 渲染器源码、JS Studio 导入逻辑）。当前工作基于该仓库的 `WM_FORMAT_1.0.md`、`WM_FORMAT_2.0.md`、`watchface_format_old.md` 文档以及 `bfed30b9ffa04573.js`（JS 解码器）和 `WatchSkinUtils.lua`（Lua 解码器）进行实现。

---

## 原版说明

Flue 项目已停止维护，中考结束后将重构 Flue-next。

`Flue-compose` 是一个面向 Android 手表的 Compose 启动器公开版仓库。

~~⚠️内含大量史山以及vibe coding出来的低质量代码，高血压患者慎入！！~~

## 仓库说明

- 本仓库是从私有开发仓库整理出的公开版本。
- 不保留原私有仓库 Git 历史。
- 不包含真实签名文件。

## 分支说明

- `main`
  - 当前公开最新版。
  - 保留 `jb_watch` 支持（残废）。

- `classic/beta1.2`
  - `main` 主线旧版本的经典快照。
  - 对应历史 `beta1.2` 公开整理版，偏向更早期的主线形态。
  - 主要用于保留旧版结构和旧时期实现参考。

- `youzipi/fix-1.4`
  - 基于 `youzipi/fix-beta1.4-performance-and-shortcuts` 整理出的公开分支。
  - 偏向 `beta1.4` 时期的性能与快捷交互修复线。

## 主要能力

- Android 手表桌面 / 启动器入口
- 蜂窝与列表两种应用抽屉
- Smart Stack / 副一屏
- 通知监听与通知中心
- 内置图片 / 视频表盘
- 外部 `jb_watch` 表盘导入
- 主题、动画、图标与显示适配设置

## 开发文档

- 开发环境、构建方式和目录说明见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)

## 开源协议

本仓库遵循 `GNU General Public License v3.0`。

- 完整协议见 [LICENSE](LICENSE)
- 若你基于本仓库修改并分发，请遵守 GPLv3 的相应要求

## 构建说明

默认使用 Android Studio 或 Gradle 构建：

```powershell
.\gradlew.bat assembleRelease --no-daemon --console=plain
```

若需要发布签名，请自行提供本地签名配置。公开仓库不会包含真实 `keystore` 或密码配置。
