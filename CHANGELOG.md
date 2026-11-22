# 更新日志

## 0.2.0

- CLI 新增 `--version`、分命令帮助和 UTF-8 配置文件。一个配置可供检查和构建共用，CLI 可覆盖其中的值。
- 控制流提供 `off`、`balanced`、`strong` 档位。增强档增加 switch 分支分派。字符串可选择 XOR 或 AES-GCM，也可保留明文。
- JVM 表达式增加 VarHandle 访问模式，并加入可序列化捕获 Lambda 的回归用例。
- Loader 由 32 个运行时类及每组独立的 JNI 桥构成。每次构建从随机种子计算资源命名空间，校验资源目录、路由、索引与原生模块。
- 移除许可证命令及运行时授权检查。
- 更新桌面工作台和项目标识。

## 0.1.0

首版已上传 GitHub。默认使用 Rust 后端生成基本块或 Rust VM，通过 JNI 运行。提供方法检查、构建、Rust 源码导出和桌面窗口。 [GitHub Releases](https://github.com/Fxdtn1337/jnz/releases)
