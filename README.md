# J2Rust

<img src="src/main/resources/dev/jnz/gui/logo.png" alt="J2Rust Logo" width="112">

J2Rust 把 JAR 里的 Java 方法编译成 Rust 和 JNI 动态库。构建完成后，调用方仍然使用原来的 JAR 入口。想知道哪些方法会被转换，可以先运行 `inspect`。想看生成的 Rust，可以运行 `emit-rust`。

## Releases

| 版本 | 说明 |
| --- | --- |
| `0.1.0` | 首版已上传 GitHub。下载入口见 [GitHub Releases](https://github.com/Fxdtn1337/jnz/releases)。 |
| `0.2.0` | [下载 JAR 和源码](https://github.com/Fxdtn1337/jnz/releases/tag/v0.2.0)。新增构建配置、分命令帮助和混淆选项，移除了旧版许可证系统。 |

各版本的功能变化见 [更新日志](CHANGELOG.md)。

## 快速开始

准备 Java 8 或兼容的较新 JDK、Rust 工具链和 Zig。Rust 需要安装目标平台的标准库和链接器。编译工具依赖的 Java 库在 `libs/`，已缓存 Gradle 7.6.4 的环境可离线构建。

```powershell
.\gradlew.bat --offline build
java -jar out\j2rust.jar --version
java -jar out\j2rust.jar gui
```

仓库附带一个 SnakeGame 样例。下面的命令先查看 `Window`，再使用仓库内的配置构建整个样例。

```powershell
java -jar out\j2rust.jar inspect --input demo\SnakeGame.jar --include Window
java -jar out\j2rust.jar build --config j2rust.example.properties
java -Xverify:all -jar out\SnakeGame-configured.jar
```

如果只想看 Rust 源码，直接导出即可。方法 ID 和原始签名在同目录下的 `frontend.json`。

```powershell
java -jar out\j2rust.jar emit-rust --input demo\SnakeGame.jar --output out\SnakeGame-source --include Window
```

## 配置文件

复制 [j2rust.example.properties](j2rust.example.properties) 后修改输入、输出和保护选项。文件使用 UTF-8 Java properties 格式，键名就是命令行选项去掉前缀 `--`。相对路径从配置文件的位置算起。Windows 路径可用 `C:/tools/rust/bin/rustc.exe` 的写法。

```properties
input=demo/SnakeGame.jar
output=out/SnakeGame-configured.jar
native-backend=rust
flow-profile=balanced
string-cipher=aes
jni-bridges=4
strict=false
```

命令行同名选项优先。例如单次构建可用 `--flow-profile strong`，也可用 `--no-strict` 关闭配置中的严格模式。同一份构建配置可传给 `inspect`，它只使用检查相关的键。配置项名拼写错误或重复时，CLI 会显示对应文件和键名。

## 命令和帮助

```powershell
java -jar out\j2rust.jar --help
java -jar out\j2rust.jar help build
java -jar out\j2rust.jar emit-rust --help
```

| 选项 | 用途 |
| --- | --- |
| `--include` / `--exclude` | 用逗号分隔类名、`类#方法`、`类#方法(参数)返回值`或 `@包名/注解名`。支持 `*`、`?`。 |
| `--strict` / `--no-strict` | 设置遇到已选但无法转换的方法时是否中止。普通模式继续构建并在报告中给出原因。 |
| `--native-backend rust\|zig` | 默认 Rust，Zig 兼容后端可显式选择。Rust 的 JNI 辅助静态库也由 Zig 编译。 |
| `--vm-coverage direct\|full\|partial` | Rust 默认直编，含异常表的方法交给 VM。使用 `partial --vm-include GLOB` 手动选定 VM 方法。 |
| `--flow-profile off\|balanced\|strong` | 默认 `balanced`，把符合条件的跳边汇入按键分派。增强档还处理符合条件的 switch 分支。 |
| `--string-cipher off\|xor\|aes` | 默认 AES-GCM。还原所需的数据随输出分发，适合保护字面量。 |
| `--jni-bridges N` | 以类为单位继续划分模块，每组生成独立 JNI 桥，模块实际数量会受已选类及原有分组影响。 |
| `--targets` | 默认为 `host`。交叉编译传入目标列表，例如 `x86_64-windows-gnu,aarch64-linux-musl`。 |
| `--report` / `--work-dir` | 分别指定 JSON 报告和生成源码、动态库、日志的目录。 |

## 转换边界

数组与多维数组、捕获 Lambda、方法引用、动态字符串拼接、动态常量和 VarHandle 访问模式已有差分用例。带异常表的方法在 Rust VM 中运行。构造器可提取符合条件的代码段，父类或同类构造调用及 JVM 要求留在初始化器中的 final 写入仍在原位置。特定 JAR 的覆盖情况以报告中的方法、初始化器和跳过明细为准。

打包时，ASM 注入 32 个 Loader 运行时类及每组独立的 JNI 桥。每次构建重新计算资源命名空间和分片名。Loader 校验目录、路由、索引与模块，再交由 JVM 的标准加载入口处理。

- 默认报告位于 `<输出 JAR>.reports/frontend-skips.json`，可以先在里面查方法名、原因和生成 ID。
- Rust 和 Zig 可通过 `--rustc` 与 `--zig` 指定。遇到链接失败时，在构建目录查看 `rustc.log` 和 `zig-support.log`。
- 受保护 JAR 运行时需要与 JNI 动态库匹配的 JVM 架构。用 `java -Xverify:all -jar ...` 验证实际入口。
- 输入已经签名时，先转换签名前的 JAR，然后对输出重新签名。

更多实现细节见 [技术规格](docs/TECHNICAL-SPEC.md)。
