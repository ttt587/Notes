# 便签置顶验收

`PinInstrumentation` 在目标应用进程内调用实际 ContentProvider、SQLiteOpenHelper、列表多选菜单和编辑页。测试创建独立便签夹具，保留最后一条置顶便签供进程重启复验，不删除用户已有便签。请在测试模拟器运行。

## 团队 Gradle 环境

```powershell
.\gradlew.bat -I tests/pinning/acceptance.gradle assembleDebug assembleDebugAndroidTest
adb install -r build/outputs/apk/debug/Notes-debug.apk
adb install -r build/outputs/apk/androidTest/debug/Notes-debug-androidTest.apk
adb shell pm grant net.micode.notes android.permission.POST_NOTIFICATIONS
adb shell appops set net.micode.notes SCHEDULE_EXACT_ALARM allow
adb shell am instrument -w net.micode.notes.tests.pin/net.micode.notes.tests.PinInstrumentation
adb shell am force-stop net.micode.notes
adb shell am instrument -w -e mode restart net.micode.notes.tests.pin/net.micode.notes.tests.PinInstrumentation
```

主验收预期 43 项通过，重启验收预期 2 项通过。`force-stop` 在这里仅验证数据库置顶状态持久化，不代表普通退出、设备重启或提醒恢复测试。

截图保存在目标应用的外部私有目录 `files/pin-evidence/`，可用 `adb pull /sdcard/Android/data/net.micode.notes/files/pin-evidence/` 导出。主测试验证历史 2/3/4 版数据库升级到 5 版、默认状态、批量操作、系统/普通文件夹及回收站过滤、排序、列表绑定、菜单分派、字数统计与提醒保存/取消共存。

## 本次离线验证

本机缺少 Gradle 8.9 缓存和 SDK 34，镜像域名解析失败。实际采用 SDK 36.1 的 aapt2、javac、D8、zipalign、apksigner 生成调试 APK，在独立 AOSP API 36 模拟器执行上述验收。目标 SDK 仍为 34；这不替代团队 AGP 8.7.3 / Gradle 8.9 / compileSdk 34 构建和 lint。详见 `doc/members/leader/test-record.md`。
