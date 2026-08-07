# AABViewer V1 Android 本地安装方案设计文档

## 1. 项目背景

当前 AABViewer 已具备 AAB 文件查看、解析以及通过电脑辅助安装能力。

现有流程：

```
AAB 文件

    ↓

电脑运行 bundletool

    ↓

adb install

    ↓

手机安装
```

存在问题：

- 必须依赖电脑
- 必须开启 USB 调试
- 测试人员使用成本较高
- 无法实现移动端完整闭环

因此增加 Android 本地转换能力。

目标：

> 在 Android 手机上直接选择 `.aab` 文件，完成 AAB → APK → 安装全过程。

---

# 2. V1版本目标

## 2.1 核心目标

实现：

```
手机

选择 xxx.aab

        ↓

AABViewer

        ↓

bundletool-core

        ↓

生成 universal.apk

        ↓

Android 系统安装

        ↓

完成安装
```

---

# 3. 功能范围

## 3.1 支持功能

|功能|支持|
|-|-|
|选择AAB文件|✅|
|解析AAB|✅|
|生成Universal APK|✅|
|APK签名|✅|
|调用系统安装器|✅|
|显示转换状态|✅|
|Android 8+|✅|

---

## 3.2 暂不支持

|功能|版本|
|-|-|
|Split APK|V2|
|ABI拆分|V2|
|Density拆分|V2|
|语言拆分|V2|
|Dynamic Feature|V2|
|Google Play完整优化策略|不支持|

---

# 4. 技术架构

```
AABViewer

├── app
│
│   ├── UI
│   ├── 文件选择
│   └── 流程控制
│
├── bundletool-core
│
│   ├── AAB解析
│   ├── APK生成
│   └── APK签名
│
└── installer
    └── Android APK安装模块
```

---

# 5. Module设计

## 5.1 app模块

职责：

- 用户交互
- 文件选择
- 转换任务管理
- 状态展示


主要流程：

```
MainActivity

    |
    |
选择AAB

    |
    |
开始转换

    |
    |
安装APK
```

---

## 5.2 bundletool-core模块

职责：

完成：

```
AAB

↓

Universal APK
```

目录：

```
bundletool-core

src/main/java

com.corlebell.bundletool

├── builder
│
│   └── UniversalApkBuilder
│
├── bundle
│
├── apk
│
├── pipeline
│
└── signer
```

---

## 5.3 installer模块

职责：

调用 Android 安装流程。

接口：

```kotlin
interface ApkInstaller {

    fun install(apk: File)

}
```

---

# 6. bundletool裁剪方案

基于：

Google bundletool


保留：

```
bundletool

├── bundle
├── apk
├── pipeline
├── android
├── signer
├── io
└── util
```


删除：

```
commands/install

device/adb

server

cli
```

原因：

V1不需要：

- adb
- split安装
- 远程设备控制

---

# 7. Gradle配置


## bundletool-core/build.gradle


```gradle
plugins {

    id 'com.android.library'

    id 'org.jetbrains.kotlin.android'

}


android {

    namespace "com.corlebell.bundletool"

    compileSdk 35


    defaultConfig {

        minSdk 26

    }

}


dependencies {


    implementation(
        "com.google.guava:guava:33.3.1-android"
    )


    implementation(
        "com.google.protobuf:protobuf-javalite:4.29.3"
    )


    implementation(
        "com.google.code.gson:gson:2.11.0"
    )


    implementation(
        "com.android.tools.build:apksig:8.7.3"
    )

}
```

---

# 8. AAB转换流程

整体流程：

```
input.aab

    |

BundleParser

    |

AppBundle

    |

ApkGenerationPipeline

    |

unsigned.apk

    |

ApkSigner

    |

universal.apk
```

---

# 9. 核心接口设计


## UniversalApkBuilder


```kotlin
class UniversalApkBuilder {


    fun build(
        aab: File,
        output: File
    ): File {


    }


}
```

输入：

```
xxx.aab
```

输出：

```
universal.apk
```

---

# 10. 文件处理


Android无法直接访问：

```
/storage/emulated/0/test.aab
```

推荐：

使用：

```
ACTION_OPEN_DOCUMENT
```


流程：

```
content://xxx

        ↓

复制

        ↓

cacheDir/input.aab
```

---

# 11. 签名方案


## V1方案

使用内置测试签名。


原因：

- 面向测试安装
- 无需用户输入keystore
- 简化流程


流程：

```
unsigned.apk

↓

ApkSigner

↓

universal.apk
```

---

# 12. APK安装方案


Android 8+：

需要：

```xml
<uses-permission
android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
```


安装流程：

```
universal.apk

↓

FileProvider

↓

Intent

↓

系统安装器
```

---

# 13. 转换任务线程


转换不能运行在主线程。


推荐：

Coroutine。


示例：

```kotlin
viewModelScope.launch {


    state = BUILDING


    val apk =
        withContext(
            Dispatchers.IO
        ){

            builder.build(
                aab
            )

        }


    state = INSTALL

}
```

---

# 14. 状态设计


```kotlin
enum class ConvertState {


    IDLE,


    COPYING,


    PARSING,


    BUILDING,


    SIGNING,


    INSTALLING,


    SUCCESS,


    ERROR

}
```

---

# 15. 异常处理


## AAB损坏

提示：

```
AAB文件无法解析
```


---

## 签名失败

提示：

```
APK签名失败
```


---

## 空间不足

检查：

```
剩余空间 >= AAB大小 * 3
```

---

# 16. 性能预估


|AAB大小|预计耗时|
|-|-|
|20MB|5~10秒|
|100MB|20~40秒|
|500MB|1~3分钟|

---

# 17. 开发计划


## Sprint 1：bundletool移植

任务：

- 创建Android Library
- 导入bundletool源码
- 修复依赖
- 编译通过


验收：

```
bundletool-core build成功
```


---

## Sprint 2：AAB转换


完成：

```
AAB

↓

universal.apk
```


验证：

转换结果与电脑bundletool一致。


---

## Sprint 3：APK安装


完成：

```
APK

↓

系统安装
```


---

## Sprint 4：UI完善


增加：

- 转换进度
- 历史记录
- APK信息展示

---

# 18. 验收标准


输入：

```
test.aab
```


操作：

```
点击开始安装
```


结果：

```
转换成功

安装成功

APP正常启动
```

---

# 19. 后续版本规划


## V2

支持：

```
AAB

↓

Split APK

↓

PackageInstaller.Session
```

增加：

- ABI选择
- Density选择
- Android版本适配
- 安装体积优化


---

## V3

升级为完整开发者工具：

功能：

- APK信息查看
- Manifest查看
- 权限分析
- SDK版本分析
- 资源查看
- 包体分析


---

# 20. 推荐开发策略


不要直接修改现有项目。


新建分支：

```
feature/android-installer
```


目录：

```
aabViewer

+
bundletool-core

+
installer
```


第一目标：

```
AAB

↓

universal.apk
```


不要一开始实现：

- Split APK
- DeviceSpec
- Play Store同级逻辑


先完成移动端闭环。