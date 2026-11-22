package dev.jnz;

import java.util.Arrays;

/** CLI 和 GUI 共用的版本与命令说明。 */
public final class CliInfo {
    public static final String VERSION = version();
    public static final String Versions = "0.2.0";
    private static final String BANNER = "J2Rust " + VERSION + "  |  JVM 字节码 → Rust / JNI";
    private static final String CONFIG = "  --config FILE      读取 UTF-8 .properties，命令行同名选项优先\n";
    private static final String FILTER = "  --include GLOB     选择包、类、方法或注解，逗号分隔，默认 *\n"
            + "  --exclude GLOB     从已选集合中排除，逗号分隔\n"
            + "  --strict           已选方法无法转换时终止，可用 --no-strict 覆盖配置\n"
            + "  --report FILE      指定 JSON 报告路径\n";
    private static final String PROTECTION = "  --flow-profile off|balanced|strong    默认 balanced\n"
            + "  --string-cipher off|xor|aes            默认 aes\n"
            + "  --pre-obfuscation full|off             控制转换前的混淆，默认 full\n"
            + "  --vm-coverage direct|full|partial      Rust 默认 direct，Zig 默认 full\n"
            + "  --vm-include GLOB                      partial 模式选取 VM 方法\n"
            + "  --jni-bridges N                        桥接组目标数，1–256\n"
            + "  --module-size SIZE                     按估计的 IR 大小分组，支持 K/M\n";

    private CliInfo() {}

    public static boolean isVersion(String[] args) {
        return args.length == 1 && Arrays.asList("--version", "-V", "version").contains(args[0]);
    }

    /** 返回目标命令，空字符串表示总帮助，null 表示执行命令。 */
    public static String requestedHelp(String[] args) {
        if (args.length == 0 || isHelpFlag(args[0])) return "";
        if (args[0].equals("help")) return args.length < 2 ? "" : args[1];
        if (args.length == 2 && isHelpFlag(args[1])) return args[0];
        return null;
    }

    public static String help(String command) {
        switch (command) {
            case "":
                return BANNER + "\n\n"
                        + "用法  java -jar j2rust.jar <命令> [选项]\n"
                        + "  gui        打开桌面工作台\n"
                        + "  inspect    查看可转换的方法和跳过原因\n"
                        + "  build      编译 JNI 库并输出受保护的 JAR\n"
                        + "  emit-rust  导出 Rust 源码和方法映射\n"
                        + "  --version  显示版本\n"
                        + "  help CMD   查看指定命令，例如 help build";
            case "gui":
                return BANNER + "\n\njava -jar j2rust.jar gui\n"
                        + "在窗口中选择输入 JAR、输出位置与目标平台。先检查方法，再构建。";
            case "inspect":
                return BANNER + "\n\njava -jar j2rust.jar inspect --input APP.jar [选项]\n\n"
                        + CONFIG + FILTER
                        + "\n例如  inspect --config j2rust.example.properties --include Window";
            case "build":
                return BANNER + "\n\njava -jar j2rust.jar build --input APP.jar --output PROTECTED.jar [选项]\n\n"
                        + CONFIG + FILTER + PROTECTION
                        + "  --native-backend rust|zig     默认 rust\n"
                        + "  --targets host|TRIPLE,...     默认 host，例如 x86_64-windows-gnu\n"
                        + "  --rustc FILE / --zig FILE     指定本地编译器\n"
                        + "  --shard-size SIZE            单个资源分片大小，支持 K/M\n"
                        + "  --work-dir DIR               保留生成源码、模块与构建日志的位置\n"
                        + "\n例如  build --config j2rust.example.properties --jni-bridges 4";
            case "emit-rust":
                return BANNER + "\n\njava -jar j2rust.jar emit-rust --input APP.jar --output DIR [选项]\n\n"
                        + CONFIG + FILTER + PROTECTION
                        + "\n按组写入 module.rs、vm.rs 和所需数据。方法映射位于输出目录中的 frontend.json。";
            default:
                throw new IllegalArgumentException("未知帮助命令 " + command + "，可用 --help 查找命令");
        }
    }

    private static boolean isHelpFlag(String token) {
        return "--help".equals(token) || "-h".equals(token);
    }

    private static String version() {
        String value = CliInfo.class.getPackage().getImplementationVersion();
        return value == null ? Versions : value;
    }
}
