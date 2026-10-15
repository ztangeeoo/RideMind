# RideMind

RideMind 是一个面向独自骑行者的 Android AI 语音陪伴 MVP。

它不是“会念数字的码表”，而是尝试成为一个懂得何时开口、何时保持安静的骑行伙伴。

## 当前 MVP

- Android 前台 GPS 骑行记录，支持锁屏运行
- 骑行时长、距离、当前速度、平均速度
- 本地骑行阶段识别：静止、出发、巡航、爬坡、恢复
- 情境化语音：出发、巡航、爬坡、坡顶、每 5 km、满 30 分钟和结束回顾
- 简洁、标准、健谈三档陪伴密度
- 静音 10 分钟及立即恢复
- 系统中文 TTS 与音乐 ducking
- 高速或下坡情境静默
- 播报/丢弃日志及“有用 / 打扰”反馈

## 产品原则

- 陪伴是核心，安全规则是护栏
- LLM 不决定遥测事实、优先级或是否开口
- 沉默不是错误
- 数据质量不足时不猜测
- 当前数据仅在本机处理

## 暂不包含

心率带、导航、实时路况、开放式 LLM 对话、心理/疲劳推断、云同步和会话恢复。

## 构建

要求：JDK 17、Android SDK 35。

```powershell
$env:JAVA_HOME='D:\Application\OpenJdk17'
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 测试

当前包含纯 Kotlin 单元测试，覆盖：

- GPS 异常跳点过滤
- 出发到巡航的阶段转换
- 持续爬坡识别
- 出发欢迎去重
- 高速下坡静默
- 临时静音
- 距离里程碑去重
- 过期事件丢弃

## 真机验证

安装和三轮试骑流程见 [MVP 真机试骑说明](docs/MVP-真机试骑说明.md)。

产品边界、状态与约束见 [MVP 能力合同](docs/MVP-能力合同.md)。

> RideMind 当前是实验性陪伴工具，不是导航、道路危险预警、医疗或训练诊断工具。骑行时请始终以观察实际道路为先。

## License

MIT
