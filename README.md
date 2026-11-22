# J2Rust

JVM 字节码到 Rust 的离线编译器。它从 JAR 读取 class 并生成 Rust 源码，再由 `rustc` 编译为 JNI 动态库。普通方法默认生成 Rust 基本块，含异常处理表的方法由 Rust VM 执行。JNI 对象访问、异常路由和认证依赖项目内 Zig 编译的辅助静态库。CLI 与中文 Swing GUI 复用入口 `dev.jnz.Main`。

## 本机启动

在项目根目录用 Java 8 运行。Gradle Wrapper 从本机缓存启动 7.6.4，ASM 和 Bouncy Castle 从 `libs/` 读取。Rust 编译器优先读取 `--rustc`、`JNZ_RUSTC` 和 `out/tools/rust/bin/`，最后使用 PATH。Rust 构建还需要目标标准库和链接器。

```powershell
.\gradlew.bat --offline build
java -jar out\j2rust.jar --help
java -jar out\j2rust.jar gui
```

辅助 Zig 优先查找工作目录或编译器 JAR 附近的 `zig.exe`、`zig` 或 `zig*/`，其次读取 `JNZ_ZIG` 和 PATH。也可用 `--zig` 指定。业务 JAR 运行时只需要对应平台的 JVM。

## 检查与构建

```powershell
java -jar out\j2rust.jar inspect --input demo\SnakeGame.jar --include 'Window'
java -jar out\j2rust.jar build --input demo\SnakeGame.jar --output out\SnakeGame-rust.jar --jni-bridges 4
java -Xverify:all -jar out\SnakeGame-rust.jar
java -jar out\j2rust.jar emit-rust --input demo\SnakeGame.jar --output out\SnakeGame-rust-source --jni-bridges 4
```

| 参数 | 行为 |
| --- | --- |
| `--include`、`--exclude` | 使用逗号分隔的内部类名、`类#方法`、`类#方法(参数)返回值` 或 `@包名/注解名`。支持 `*` 和 `?`。先包含再排除。 |
| `--strict` | 有已选择但不可转换的方法时写报告，退出并保留已有输出。使用精确规则可以避开构造器和引导代码。 |
| `--vm-coverage direct\|full\|partial` | Rust 默认 `direct`。`full` 让所有方法进入 VM。使用 `partial --vm-include GLOB` 选定进入 VM 的方法。含异常处理表的方法始终使用 VM。 |
| `--native-backend rust\|zig` | Rust 是默认后端。Zig 是可明确选择的兼容后端。 |
| `--jni-bridges 4` | 在当前按包和大小的分组上继续拆分为不同的 JNI 桥接模块。每个类保留在一个组。 |
| `--pre-obfuscation full\|off` | 默认在转换前编码所选方法的字符串字面量、生成调用点并改写跳边。Loader 在打包时独立处理。 |
| `--targets host` | 可改为 `x86_64-windows-gnu,aarch64-linux-musl` 等逗号分隔的目标列表。 |
| `--module-size 1M` | 同包内按 IR 大小估计切分独立库，类是分组的最小单位。 |
| `--shard-size 1M` | JAR 中单个原生资源块的字节上限，支持 `K` 和 `M` 后缀。 |
| `--report FILE` | 输出转换清单与跳过原因。默认路径为 `<目标 JAR>.reports/frontend-skips.json`，检查命令使用输入 JAR 名。 |
| `--work-dir DIR` | 存放生成的 `module.rs`、`vm.rs`、认证程序数据、独立动态库和编译日志。默认路径为 `<目标 JAR>.build/`。 |

当前支持数组、多维数组、捕获 Lambda、方法引用、动态拼接、动态常量、类型检查、对象创建、异常处理和同步块等字节码。可安全切分的 `<init>` 和 `<clinit>` 提取为 native 辅助方法。原始构造调用和必须在初始化器内执行的 final 字段写入留在 JVM。输入支持 major 49–71，低版本启动器按原类保留。输入已签名时应先从签名前的 JAR 构建，再为输出重新签名。

输出 Loader 按资源读取、平台识别、校验装载、逐类绑定、字面量还原和许可证等职责拆分。每次构建重新生成内部类名、成员名、JNI 桥接符号和字符串掩码，并且改写 Loader 的跳边。

## 离线许可证

```powershell
$hwid = java -jar out\j2rust.jar license fingerprint
$expiry = [DateTimeOffset]::UtcNow.AddDays(30).ToUnixTimeSeconds()
java -jar out\j2rust.jar license keygen out\vendor.pk8 out\vendor.spki
java -jar out\j2rust.jar license issue out\vendor.pk8 $hwid $expiry 5 out\license.dat
java -jar out\j2rust.jar license verify out\vendor.spki out\license.dat $hwid 1
java -jar out\j2rust.jar build --input out\input.jar --output out\licensed.jar --include 'example/*#compute*' --public-key out\vendor.spki --license out\license.dat --features 1
```

签发端保存 PKCS#8 私钥。编译端使用 SPKI 公钥和签发结果。许可校验发生在保护类首次绑定 native 方法时。

## 复跑验收

```powershell
python tests\verify.py
python tests\verify.py --cross
python tests\verify_snake.py
python tests\verify_linkage.py --direct
python tests\verify_initializers.py --native-backend rust
python tests\verify_expressions.py
```

脚本编译 `examples/` 中的样例并校验行为差异、方法标志、版本范围、混合执行、模块分片和授权失败路径。`--cross` 的八目标产物检查使用 Zig 兼容后端。证据位于 `out/verification/`。实现与二进制格式见 [技术规格](docs/TECHNICAL-SPEC.md)。
