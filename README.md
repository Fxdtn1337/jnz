# J2Rust

<img src="src/main/resources/dev/jnz/gui/logo.png" alt="J2Rust Logo" width="112">

喜欢不妨点个star

部署前用目标 JDK 和平台跑一遍原包与转换包的回归，再核对构建报告中的跳过项、初始化器和 JVM 链接桥。

J2Rust 把 JAR 里的 Java 方法编译成 Rust 和 JNI 动态库。构建完成后，调用方仍然使用原来的 JAR 入口。想知道哪些方法会被转换，可以先运行 `inspect`。想看生成的 Rust，可以运行 `emit-rust`。

## Releases

| 版本 | 说明 |
| --- | --- |
| `1.0.0` | [下载 JAR 和源码](https://github.com/Fxdtn1337/jnz/releases/tag/v1.0.0)。扩展 Rust/JNI 执行覆盖，更新 VM 与资源分片格式。 |
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
| `--vm-coverage direct\|full\|partial` | `build` 默认使用双层 Rust VM，`emit-rust` 默认生成可读直编函数。带异常表的方法由 VM 执行。 |
| `--java-bridges allow\|reject` | `reject` 会在选中方法需要 JVM 链接桩或无法转换时中止。选择该模式时，字符串预处理默认关闭，已支持的原始字面量仍通过加密的原生元数据交付。 |
| `--flow-profile off\|balanced\|strong` | 默认 `balanced`，把符合条件的跳边汇入按键分派。增强档还处理符合条件的 switch 分支。 |
| `--string-cipher off\|xor\|aes` | 默认 AES-GCM。还原所需的数据随输出分发，适合保护字面量。 |
| `--jni-bridges N` | 以类为单位继续划分模块，每组生成独立 JNI 桥，模块实际数量会受已选类及原有分组影响。 |
| `--loader-codec auto\|raw\|deflate` | 默认 `auto` 按分片选择较小的形式。也可以显式设为原样或 DEFLATE。 |
| `--targets` | 默认为 `host`。交叉编译传入目标列表，例如 `x86_64-windows-gnu,aarch64-linux-musl`。 |
| `--report` / `--work-dir` | 分别指定 JSON 报告和生成源码、动态库、日志的目录。 |

## 转换边界

基本和引用数组的元素访问、基本数组创建、长度和部分本类字段及调用可直接走 JNI。数组与多维数组、record 合成方法、捕获 Lambda、方法引用、动态字符串拼接、动态常量和 VarHandle 访问模式有差分用例。需要 JVM 自行完成链接的指令会使用窄桥。构造器可提取符合条件的代码段，父类或同类构造调用及 JVM 要求留在初始化器中的 final 写入仍在原位置。

全 VM 模式先把方法变成 Rust 执行器可消费的业务指令，再把生成的 Rust 入口调度转换为独立的控制指令。第二层决定取指、执行、恢复异常和返回的路径。构建报告用 `vmLayers` 标出每个方法实际走的层数。

要求选中的方法不生成 Java 链接桥时，可用下面的方式构建。它只处理筛选出的目标方法，其他方法仍按输入包保留。

```powershell
java -jar out\j2rust.jar build --input demo\SnakeGame.jar --output out\SnakeGame-tuple.jar --include 'Tuple#getX' --java-bridges reject
```

打包时，ASM 注入 Loader 运行时类及每组独立的 JNI 桥。每次构建重新计算资源命名空间和分片名。Loader 校验并重组各编码分片，再交由 JVM 的标准加载入口处理。

- 默认报告位于 `<输出 JAR>.reports/frontend-skips.json`，可查各方法的 JVM 链接桩数量、跳过原因、初始化器保留指令数和常量池重建统计。
- Rust 和 Zig 可通过 `--rustc` 与 `--zig` 指定。遇到链接失败时，在构建目录查看 `rustc.log` 和 `zig-support.log`。
- 受保护 JAR 运行时需要与 JNI 动态库匹配的 JVM 架构。用 `java -Xverify:all -jar ...` 验证实际入口。
- 输入已经签名时，先转换签名前的 JAR，然后对输出重新签名。

更多实现细节见 [技术规格](docs/TECHNICAL-SPEC.md)。
