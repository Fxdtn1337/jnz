# J2Rust 技术规格

## 编译链路

`JAR → JVM 预混淆 → ASM / verifier → 槽位 IR → Rust 源码 → rustc 动态库 → JAR 资源块 → JVM 加载与逐类绑定`

| 目录                                              | 职责                                                                       |
|---------------------------------------------------|----------------------------------------------------------------------------|
| `src/main/java/dev/jnz/frontend/`                 | 检查 class 版本，按选择器和 ASM Analyzer 建立 IR，重写方法和类初始化入口。 |
| `src/main/java/dev/jnz/backend/`                  | 重排 VM 基本块、随机化指令、加密程序，生成 Rust 与兼容 Zig 后端源码。      |
| `src/main/resources/dev/jnz/backend/`             | Rust VM、辅助库的 ABI 桥接、JNI、认证，以及兼容 Zig 后端模板。             |
| `src/main/java/dev/jnz/pack/`、`runtime/`         | 资源切块和索引、平台选择、解包校验和 JVM 动态库绑定。                      |
| `src/main/java/dev/jnz/protect/`                  | 控制流变换和字面量编码。                                                   |
| `src/main/java/dev/jnz/gui/`、`dev/jnz/Main.java` | Swing 工作台、CLI、共同的执行入口。                                        |
| `examples/`、`tests/`                             | Java 8 运行样例和跨 JVM 验收。                                             |

### JVM 前端

输入 major 49 至 71 统一由 ASM 9.10.1 解析。版本字段高于 71 时以条目和版本号硬失败。低于 49 的旧启动器按原 class 保留。无法解析的 class 或无法降低的方法生成诊断。对带 `Multi-Release: true` 的 JAR，基线和每份有效覆盖 class 独立分配绑定 ID。类名、方法名和描述符按原值匹配，JNI 元数据使用 Modified UTF-8。重写可安全处理的 class 时重建常量池。未知属性或结构相同但原索引不同的动态常量会使前端保留原池。

预混淆可将被选中的非空字符串字面量改写为动态调用站点。JVM 在首次执行时还原并驻留字符串，Java 5 和 6 class 使用静态还原桩。字面量处理提供 AES-GCM、XOR 和关闭三种设置。AES-GCM 为每个字面量生成密钥和 nonce，运行时校验标签，编码后的密钥与应用一同分发。

均衡档把栈为空且帧兼容的无条件与条件跳边汇入共享 `lookupswitch` 分派。增强档还处理满足相同条件的 switch 分支。无法安全改写的区域、旧版 class 与初始化器范围沿用原有控制流。成功修改的 class 重新构造常量池，遇到未知属性或大小上限时保留原 class 交给前端诊断。

```text
for entry in sorted_class_entries:
    check_class_header_and_version(entry)
    for method in entry.methods_matching(include, exclude):
        verify_with_asm_frames(method)
        if supported(method):
            lower_to_slot_ir(method)
        else:
            report(entry, method.name, method.descriptor, reason)
    if any_selected_method_lowered:
        prepend_or_create_clinit_bind(owner_class, first_method_id)
        set_native_and_remove_code_for_lowered_methods()
if strict and any_skip:
    abort_without_replacing_output()
```

方法 IR 包含全局方法 ID、该 class 条目的首方法 ID、原条目、owner、名称、描述符、static 标记、最大局部槽数、最大栈槽数，及固定三元组序列 `(opcode, operand_a, operand_b)`。宽常量按低 32 位在前编码。局部变量槽遵循 JVM 的 `long` 和 `double` 双槽规则，VM 操作数栈每个值用一个 `u64`。栈置换读 ASM 帧中的类别宽度，经临时槽重排。整型 switch 降为保存键、逐项比较和默认分支。

对象引用使用对应调用线程的 JNI 句柄。基本数组创建、数组元素访问、长度、字符串字面量和可验证的本类字段或调用有 JNI 原生路径。需要保留 JVM 解析语义的指令通过原类中的短链接桩执行。每个桩包含一条符号操作和参数加载或返回。Rust 直编函数或 VM 处理分支、运算、循环和栈状态。局部 JNI 结果提升到外层本地引用帧，解释器定期释放已离开局部槽和栈的引用。指定 `--java-bridges reject` 时，有 JVM 链接桩或未转换的选中方法会使构建失败。

参考的本地 JNIC C 生成路径包括 `LocalVariablesGenerator` 的 JNI 参数槽、`NativeInitializerVisitor` 的初始化绑定以及 `NativeRegistrationGenerator` 的表注册。这里由 Rust 或 Zig thunk 封装同一类 JNI ABI。`NEW` 在原 PC 通过类字面量和 JNI 分配对象，对应 `invokespecial <init>` 调用构造器。构造器的 JVM 必要操作留在原方法。VM 按原异常表顺序选择处理器，使用原 Throwable 对象，未匹配时抛回 JVM。同步块由 JNI monitor 维护，解释器跟踪仍持有的锁并在异常终止时清理。

### Rust 后端

每组输出 `module.rs`、`vm.rs`、`control.rs`、`bridge.zig` 和关联的认证数据。直编模式在 `module.rs` 中为选中的方法生成 Rust JNI thunk 和独立的 Rust 基本块执行函数。全 VM 模式由 `vm.rs` 执行业务指令。生成器再把 Rust 入口的取指、执行、继续、异常恢复和返回编成一组独立控制指令，由 `control.rs` 解释。控制程序按方法置换状态位置和操作码并掩码状态字。Zig 编译 JNI 调用、对象操作、异常路由和认证静态组件。最后由 `rustc --crate-type=cdylib` 链接 Rust 模块与辅助静态组件。目标机无需编译器。

`emit-rust` 保留每组的生成源码与数据，在外部 JSON 中记录原 class、方法、描述符与生成 ID。`build` 使用同一生成器并保存 `rustc-version.log` 和 `rustc.log`。目标标准库由开发机提供，编译器本身不启动依赖解析。

### VM 格式与兼容 Zig 后端

全 VM 模式在入口、跳转目标、终止指令、异常表边界及处理器入口切分基本块。重排后复原分支目标和落空边，将原保护区间拆成物理区间并保持捕获优先顺序。重叠保护区的拆分增长超出预算时，该方法保留块布局并继续走 VM。Rust 和 Zig 都在解码后检查分支目标与异常区间边界。直编和部分模式中，带异常处理表的方法也进入 VM。

| 字段       | 编码                                                                                                                                                                                                           |
|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| VM 指令    | 密封内容起始为 `u64 LE` 方法种子。接着每条指令有 1 字节操作码和两个 `u32 LE` 操作数，按方法种子与物理 PC 进行掩码。 |
| 操作码     | 各库独立生成 256 项置换。两个 256 字节表保存置换掩码与被掩码值，执行器恢复指令后再分派。 |
| 程序资料   | 12 字节 nonce、等长 ChaCha20-Poly1305 密文、16 字节认证标签。256 位随机库密钥。                                                                                                                                |
| nonce      | 随机 8 字节库前缀加 `u32 LE method_id`。名称表同格式，将前缀首字节异或 `0x80`，末尾使用 class ID。                                                                                                             |
| 程序 AAD   | 顺序拼接 `method_id`、局部槽数和栈容量，每项为 `u32 LE`。                                                                                                                                                      |
| 名称表 AAD | 顺序拼接 `class_id` 和方法数量，每项为 `u32 LE`。明文是按 JNI 表顺序排列的两个 NUL 终止 Modified UTF-8 字符串。                                                                                                |
| 符号调用表 | 4 字节 `u32 LE` 数量和每项 4 字节表内偏移，后接 NUL 终止 owner。各项保存 NUL 终止名称和描述符、`u8` 操作类型、`u8` 参数数量、`u8` 返回类型及参数类型。nonce 前缀首字节异或 `0x40`，AAD 是 `u32 LE method_id`。 |
| 异常处理表 | 每项 4 个 `u32 LE`，分别为起始 PC、排除的结束 PC、目标 PC、catch 类型的符号调用索引。全匹配类型以 `0xffffffff` 表示。nonce 前缀首字节异或 `0x20`，AAD 是 `u32 LE method_id`。                                  |

解释器逐调用认证解密，返回或失败时擦除解密后的缓冲区、栈和局部槽。每个库独立生成 opcode、密钥和方法布局。Zig 编译使用 `build-lib -dynamic -O ReleaseSafe -fPIC -fstrip`。内置 `build.zig` 应在编译器输出的模块目录内与 `module.zig` 一起使用。

支持 Windows GNU、Linux GNU、Linux musl 和 macOS，每个平台均提供 `x86_64` 和 `aarch64` triple。Linux 运行时以标准 musl 加载器路径为默认判断依据，可用 `-Djnz.linux.abi=gnu` 或 `musl` 显式选择。

### 模块资源

同一 Java 包中的类按 IR 字节量估计分入独立模块。`--jni-bridges` 可以继续按类切分分组。每组含实际转换的方法和独立的 JNI 桥。每个目标 triple 单独编译，每个库再按 `--shard-size` 分块。打包端与 Loader 使用同样的种子计算 `META-INF/jnz/<namespace>/`。其中的 `<triple>/<group>/index.bin` 描述一个模块。

| 偏移 | 二进制字段                   |
|------|------------------------------|
| 0    | 4 字节 `JNZ2`                |
| 4    | 4 字节 `u32 BE` 分块数       |
| 8    | 8 字节 `u64 BE` 拼接后的长度 |
| 16   | 32 字节完整动态库的 SHA-256  |
| 48   | 32 字节存储帧流的 SHA-256    |

存储帧流按块号顺序连接每个完整 `.part` 的原始内容，包含帧头。分块文件名由目标平台、组号与块号运算得出。每个文件以固定 49 字节帧头开始。

| 偏移 | 帧字段                                                            |
|------|-------------------------------------------------------------------|
| 0    | 4 字节 `JNF1`                                                     |
| 4    | 1 字节编解码编号。0 为原始字节，1 为单个 zlib 包装的 DEFLATE 流。 |
| 5    | 4 字节 `u32 BE` 逻辑块号，从 0 开始                               |
| 9    | 4 字节 `u32 BE` 解码长度                                          |
| 13   | 4 字节 `u32 BE` 帧体存储长度                                      |
| 17   | 32 字节解码分片 SHA-256                                           |
| 49   | 与帧头声明等长的帧体                                              |

`--loader-codec auto` 在单块压缩能缩小时保存 DEFLATE，否则保存原始字节。`raw` 和 `deflate` 固定使用一种编解码。解码块限 64 MiB，存储体限 65 MiB。Loader 使用固定缓冲流式解码并拒绝重复块号、错误长度、字典以及 zlib 尾随数据。只有认证目录、认证索引、各块摘要、完整帧流摘要和动态库摘要均通过后，Loader 才调用 `System.load`。JNI 通过 `RegisterNatives` 为指定 class 安装对应的方法表。

| 资源                         | 作用与格式                                                                                                              |
|------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `catalog.bin`                | 4 字节魔数 `JNC2`、4 字节大端记录数。每条含 `writeUTF` 相对路径及 32 字节 SHA-256。目录摘要写入运行时类，加载后先校验。 |
| `classes/<class_id>`         | 4 字节大端 group ID 加 `writeUTF` 原类二进制名称。                                                                      |
| `groups/<group_id>`          | 4 字节大端种子加 `writeUTF` 路由，解码后得到 JNI 类与注册方法名。                                                       |
| `<triple>/<group>/index.bin` | 模块长度、分块数量、存储帧流摘要与完整模块摘要。                                                                        |

运行时校验路由和索引时使用已校验的目录。ASM 注入 35 个运行时类及各组的 JNI 声明类，后者各包含一个注册入口。类名与成员名在打包时生成，调试标记去除。资源处理、平台识别、分片解码、校验装载、对象绑定和字面量恢复由不同部件负责。

## 工程范围

- 当前转换覆盖基本类型、引用、数组、类型检查、动态调用站点与动态常量、对象分配、异常处理、同步块和已验收的 record 合成方法。部分初始化器可把满足控制流闭合与数据边界条件的区域提取到 native，原方法保留必要的 `this/super` 调用和 final 字段写入。接口方法体、栈敏感反射调用及不能安全提取的区域以跳过报告为准。
- JAR 使用显式二进制资源与 JVM 标准加载。库镜像分块具有长度和散列验证，VM 程序与 JNI 名称表具有 AEAD 验证。VM 密钥驻留于对应客户机库中。
- Gradle Wrapper 使用实际离线可用的 7.6.4。本项目 `libs/` 固定离线依赖。版本范围的前端验收使用人工设置主版本的基本类型样例。真实 Java 7 SnakeGame 的验证由 `tests/verify_snake.py` 执行，其他实际 `javac` 和 JVM 的验证范围由 `out/verification/latest.json` 记录。
