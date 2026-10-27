# B 模块回归测试

日期：2026-10-27。生产功能代码位于 `src/`，本目录是独立验收源码。

## 时间边界测试

JDK 9 以上即可（本次 JDK 21，编译为 Java 8 字节码），不依赖 Android 或第三方测试库：

```powershell
javac --release 8 -encoding UTF-8 -d ../.work/b-jvm-tests src/net/micode/notes/tool/QuickReminder.java tests/reminder/QuickReminderTest.java
java -cp ../.work/b-jvm-tests net.micode.notes.tool.QuickReminderTest
```

共 26 个断言，固定输入时间与时区，覆盖跨天、月末、年末、闰年、夏令时、清零秒与毫秒，以及过去、当前和未来时间。

## Android 验收构建

`develop` 尚无构建配置。为了不将其他成员的源码整体合并进 B 的 PR，使用 C 成员固定提交
`09f4b0fec3444c91f6ec8a9a902cd5e3404ed5e7` 的构建和兼容性配置，在工作区 `.work/b-integration` 生成独立集成副本，再覆盖 B 的变更。需先 fetch 该提交；脚本会读取本地 Git 对象。

```powershell
python tests/reminder/prepare_integration.py
cd ../.work/b-integration
# JAVA_HOME 指向 JDK 21；SDK 路径见生成的 local.properties
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --console=plain
```

本次 SDK 位于 `E:/Sdk`，可通过 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 改写。脚本保留 C 的 Manifest exported/兼容性配置，合并 B 新增权限和恢复广播；不会修改原仓库。

测试构建额外注册 `WidgetHostActivity`，仅用于承载真实 AppWidgetHost/RemoteViews；此类和入口不属于 B 的生产 Manifest。测试 APK 使用系统 Instrumentation，不需要 AndroidX/JUnit 网络依赖。

在专用 Android API 36 模拟器上安装两个 APK，预先授予通知权限、精确闹钟权限和小组件宿主绑定权限，再执行：

```powershell
adb shell pm grant net.micode.notes android.permission.POST_NOTIFICATIONS
adb shell appops set net.micode.notes SCHEDULE_EXACT_ALARM allow
adb shell appwidget grantbind --package net.micode.notes --user 0
adb shell am instrument -w net.micode.notes.test/net.micode.notes.tests.ReminderInstrumentation
```

默认模式 37 个断言，包含真实 AlarmManager 在后台触发通知。`-e mode inexact`（先撤销精确闹钟权限）有 2 个断言，`-e mode notification_denied`（先 revoke 通知权限，并用 `pm clear-permission-flags net.micode.notes android.permission.POST_NOTIFICATIONS user-set user-fixed` 清除专用测试设备的重复拒绝标记）有 4 个断言。运行后恢复权限。`-e mode trigger` 可仅重跑 4 个取消/触发断言并补抓截图，不额外加入默认验收总数。

`-e mode after_restart` / `after_reboot` 各检查 1 个持久化断言，依赖默认模式保存的固定便签。**Instrumentation 启动和结束会强停目标进程**，所以系统闹钟恢复必须在实际重启完成后、再次开始 Instrumentation 之前，用 `dumpsys alarm` 检查。测试前正常打开应用，确认 `stopped=false`，执行真实 `adb reboot`，等待恢复的 `RTC_WAKEUP` 出现；不能只等待 `sys.boot_completed=1`，也不能把强停场景等同普通退出。

运行截图保存在应用外部私有目录 `files/b-evidence`，原始记录见 `doc/members/b/test-record.md` 和 `evidence/logs/`。测试会创建名称以 B 开头的便签，只应在专用模拟器上运行。
