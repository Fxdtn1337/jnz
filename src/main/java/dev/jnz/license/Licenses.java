package dev.jnz.license;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

/** 离线签发工具，私钥采用 PKCS#8 DER，公钥采用 X.509 SPKI DER。 */
public final class Licenses {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Licenses() { }

    public static String usage() {
        return "license fingerprint\n"
                + "license keygen <private.pk8> <public.spki>\n"
                + "license issue <private.pk8> <HWID-64hex> <expiry-epoch-seconds> <features-hex> <license.dat>\n"
                + "license verify <public.spki> <license.dat> [HWID-64hex] [required-features-hex]";
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
    }

    /** args 从子命令开始，例如 {"fingerprint"}。失败时向调用方抛出异常。 */
    public static void run(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
            System.out.println(usage());
            return;
        }
        switch (args[0]) {
            case "fingerprint":
                if (args.length != 1) {
                    throw new IllegalArgumentException(usage());
                }
                System.out.println(hex(DeviceFingerprint.current()));
                break;
            case "keygen":
                if (args.length != 3) {
                    throw new IllegalArgumentException(usage());
                }
                Path priv = Paths.get(args[1]).toAbsolutePath().normalize();
                Path pub = Paths.get(args[2]).toAbsolutePath().normalize();
                if (priv.equals(pub) || Files.exists(priv, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(pub, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("请为公钥和私钥分别提供两个尚不存在的文件路径");
                }
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = generator.generateKeyPair();
                writeNew(pub, pair.getPublic().getEncoded(), false);
                byte[] secret = pair.getPrivate().getEncoded();
                try {
                    writeNew(priv, secret, true);
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(pub);
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw e;
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
                System.out.println("已生成 P-256 密钥对");
                break;
            case "issue":
                if (args.length != 6) {
                    throw new IllegalArgumentException(usage());
                }
                long expiry = Long.parseLong(args[3]);
                long features = featureMask(args[4]);
                byte[] envelope = LicenseFile.issue(LicenseFile.readPrivate(Paths.get(args[1])),
                        parseHwid(args[2]), expiry, features);
                writeNew(Paths.get(args[5]), envelope, false);
                System.out.println("已签发许可证，大小 " + envelope.length + " 字节");
                break;
            case "verify":
                if (args.length < 3 || args.length > 5) {
                    throw new IllegalArgumentException(usage());
                }
                byte[] hwid = args.length > 3 ? parseHwid(args[3]) : DeviceFingerprint.current();
                long required = args.length > 4 ? featureMask(args[4]) : 0;
                long granted = LicenseFile.validate(LicenseFile.read(Paths.get(args[2])),
                        LicenseFile.readPublic(Paths.get(args[1])), hwid, Instant.now().getEpochSecond(), required);
                System.out.printf(Locale.ROOT, "许可证有效，已授权功能位 0x%016x%n", granted);
                break;
            default:
                throw new IllegalArgumentException(usage());
        }
    }

    public static byte[] parseHwid(String text) {
        if (text == null || !text.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("HWID 须为 64 个十六进制字符");
        }
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    public static long featureMask(String value) {
        String mask = value.replaceFirst("^(?:0x|0X)", "");
        if (!mask.matches("[0-9a-fA-F]{1,16}")) {
            throw new IllegalArgumentException("功能位须为 1 至 16 个十六进制字符");
        }
        return Long.parseUnsignedLong(mask, 16);
    }

    public static String hex(byte[] bytes) {
        char[] out = new char[2 * bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 15];
        }
        return new String(out);
    }

    private static void writeNew(Path path, byte[] bytes, boolean secret) throws IOException {
        FileAttribute<?>[] attrs = new FileAttribute<?>[0];
        if (secret && FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            attrs = new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")) };
        }
        EnumSet<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        SeekableByteChannel channel = Files.newByteChannel(path, options, attrs);
        try (SeekableByteChannel opened = channel) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                opened.write(buffer);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }
}
