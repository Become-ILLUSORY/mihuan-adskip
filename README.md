# 猛鬼宿舍 广告跳过 (Mihuan AdSkip)

LSPosed/Xposed 模块：在 **猛鬼宿舍（`com.mgss.mihuan`）** 中拦截「看广告领奖励」，
**不播放激励视频**，直接向游戏下发奖励。

## 原理

游戏是 **LayaAir (Conch) 原生引擎 + SecShell 加固**，真正逻辑在 JS（`assets/cache`）。
各渠道的广告入口都统一走：

```js
PlatformClass.createClass("demo.AD").callWithBack(cb, "ShowRewardVideo")
```

即映射到 Java 的 `demo.AD.ShowRewardVideo(String)`，播放完激励视频后回调 `cb(true)` 发奖。

本模块：

1. hook `demo.AD.ShowRewardVideo`（以及任何名字含 `reward` 的方法）；
2. `setResult(null)` 阻止广告显示；
3. 通过 `layaair.game.browser.ConchJNI.RunJS(...)` 在游戏 JS 里直接触发已注册的成功回调：
   - 优先调用 `PlatformClass.clsMap['demo.AD'].callBackList['ShowRewardVideo'](true)`；
   - 失败则回退 `conch.platCallBack({objId:-1,cName:'demo.AD',mName:'ShowRewardVideo',v:true})`。

游戏拿到 `true` 后自行发奖（金币/碎片/次数等，本地存档，无需服务端）。

## 使用方法

1. 安装 APK（本项目 CI 的 artifact 或 Releases 里下载）。
2. 打开 **LSPosed 管理器 → 模块**，启用「猛鬼宿舍 广告跳过」。
3. **作用域**勾选「猛鬼宿舍 / `com.mgss.mihuan`」。
4. 强制停止游戏进程后重新打开。
5. 游戏内点「看广告」按钮 → 不播广告，直接到账。

## 排查

```bash
adb logcat -s MihuanAdSkip
```

日志会打印：

- `hooked demo.AD.ShowRewardVideo(...)`   —— hook 成功
- `RunJS ret=CB_OK` / `PLAT_OK`           —— 奖励回调已送达
- `RunJS ret=NONE`                        —— 未找到回调（可能需要更新适配）
- `demo.AD not found before timeout`      —— 类没加载到（确认作用域/Dex 加固）

## 构建

仓库已配置 GitHub Actions（`.github/workflows/build.yml`），推送到 `main` 或打 `v*` tag
自动构建并在 Actions artifact / Release 中提供 `app-debug.apk`（debug 签名，可直接安装）。

本地构建：

```bash
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Android SDK 34、Gradle 8.7。

## 说明

- 仅对 `com.mgss.mihuan` 生效（作用域限制）。
- 奖励为游戏本地数据，纯客户端发放；模块不改动服务器。
- 加固类名/方法名如未来版本变更，日志会提示，按 `RunJS ret=NONE` 调整即可。
