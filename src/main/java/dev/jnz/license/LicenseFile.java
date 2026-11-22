package dev.jnz.license;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** 离线许可证的固定长度编码，所有多字节字段使用网络字节序。 */
public final class LicenseFile {
    public static final int BODY_SIZE = 56;
    public static final int SIZE = BODY_SIZE + 64;
    private static final int MAGIC = 0x4a4e5a4c; // JNZL
    private static final int VERSION = 1;
    private static final int ALGORITHM = 1;
    private static final BigInteger ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);

    private LicenseFile() { }

    public static PublicKey readPublic(Path path) throws IOException, GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(readBounded(path, 4096)));
        requireP256(key);
        return key;
    }

    public static PrivateKey readPrivate(Path path) throws IOException, GeneralSecurityException {
        byte[] encoded = readBounded(path, 4096);
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            requireP256(key);
            return key;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    public static byte[] read(Path path) throws IOException {
        byte[] bytes = readBounded(path, SIZE);
        if (bytes.length != SIZE) {
            throw new IOException("许可证长度必须为 " + SIZE + " 字节");
        }
        return bytes;
    }

    /** 将签发公钥转为 04 || X || Y，每个坐标固定 32 字节。 */
    public static byte[] sec1(PublicKey key) throws GeneralSecurityException {
        requireP256(key);
        ECPublicKey ec = (ECPublicKey) key;
        byte[] out = new byte[65];
        out[0] = 4;
        putUnsigned(ec.getW().getAffineX(), out, 1);
        putUnsigned(ec.getW().getAffineY(), out, 33);
        return out;
    }

    public static byte[] issue(PrivateKey key, byte[] hwid, long expiry, long features)
            throws GeneralSecurityException {
        requireP256(key);
        if (hwid.length != 32 || expiry <= 0) {
            throw new IllegalArgumentException("签发需要 32 字节设备指纹和正数到期时间");
        }
        ByteBuffer out = ByteBuffer.allocate(SIZE);
        out.putInt(MAGIC).putShort((short) VERSION).putShort((short) ALGORITHM);
        out.put(hwid).putLong(expiry).putLong(features);

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(out.array(), 0, BODY_SIZE);
        byte[] raw = fromDer(signer.sign());
        out.put(raw);
        return out.array();
    }

    /** 验签并检查硬件、到期时间和所有必需的功能位，返回已授权功能位。 */
    public static long validate(byte[] envelope, PublicKey key, byte[] hwid, long now, long required)
            throws GeneralSecurityException {
        if (envelope.length != SIZE || hwid.length != 32) {
            throw new SignatureException("许可证长度或设备指纹长度错误");
        }
        requireP256(key);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(envelope, 0, BODY_SIZE);
        if (!verifier.verify(toDer(envelope, BODY_SIZE))) {
            throw new SignatureException("许可证签名无效");
        }
        return checkPolicy(envelope, hwid, now, required);
    }

    /** 调用方已独立完成签名验证时，用此方法解释已认证的头与授权字段。 */
    public static long checkPolicy(byte[] envelope, byte[] hwid, long now, long required)
            throws GeneralSecurityException {
        if (envelope.length != SIZE || hwid.length != 32) {
            throw new GeneralSecurityException("许可证长度或设备指纹长度错误");
        }
        ByteBuffer in = ByteBuffer.wrap(envelope);
        if (in.getInt() != MAGIC || in.getShort() != VERSION || in.getShort() != ALGORITHM) {
            throw new GeneralSecurityException("许可证头、版本或算法无效");
        }
        byte[] expected = new byte[32];
        in.get(expected);
        long expiry = in.getLong();
        long features = in.getLong();
        if (!MessageDigest.isEqual(expected, hwid)) {
            throw new GeneralSecurityException("许可证绑定的设备指纹不匹配");
        }
        if (now < 0 || expiry <= 0 || now >= expiry) {
            throw new GeneralSecurityException("许可证已到期或时间无效");
        }
        if ((features & required) != required) {
            throw new GeneralSecurityException("许可证缺少所需功能位");
        }
        return features;
    }

    private static void requireP256(Key key) throws GeneralSecurityException {
        if (!(key instanceof ECKey)) {
            throw new GeneralSecurityException("需要 P-256 EC 密钥");
        }
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = params.getParameterSpec(ECParameterSpec.class);
        ECParameterSpec actual = ((ECKey) key).getParams();
        if (actual == null || !expected.getCurve().equals(actual.getCurve())
                || !expected.getGenerator().equals(actual.getGenerator())
                || !expected.getOrder().equals(actual.getOrder())
                || expected.getCofactor() != actual.getCofactor()) {
            throw new GeneralSecurityException("许可证只接受 P-256 曲线");
        }
    }

    private static byte[] readBounded(Path path, int max) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = new byte[max + 1];
            int used = 0;
            while (used < bytes.length) {
                int n = in.read(bytes, used, bytes.length - used);
                if (n < 0) {
                    return Arrays.copyOf(bytes, used);
                }
                used += n;
            }
            throw new IOException("文件超过允许长度 " + max + " 字节");
        }
    }

    private static void putUnsigned(BigInteger value, byte[] out, int offset)
            throws GeneralSecurityException {
        byte[] encoded = value.toByteArray();
        int start = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
        int length = encoded.length - start;
        if (value.signum() < 0 || length > 32) {
            throw new GeneralSecurityException("P-256 整数超出范围");
        }
        System.arraycopy(encoded, start, out, offset + 32 - length, length);
    }

    private static void checkScalar(BigInteger scalar) throws SignatureException {
        if (scalar.signum() <= 0 || scalar.compareTo(ORDER) >= 0) {
            throw new SignatureException("ECDSA 签名整数超出范围");
        }
    }

    private static BigInteger derInteger(ByteBuffer in) throws SignatureException {
        if (in.remaining() < 3 || in.get() != 2) {
            throw new SignatureException("ECDSA DER 整数标签无效");
        }
        int len = in.get() & 255;
        if (len < 1 || len > 33 || len > in.remaining()) {
            throw new SignatureException("ECDSA DER 整数长度无效");
        }
        byte[] bytes = new byte[len];
        in.get(bytes);
        if (len > 1 && bytes[0] == 0 && bytes[1] >= 0) {
            throw new SignatureException("ECDSA DER 整数包含冗余前导零");
        }
        BigInteger value = new BigInteger(bytes);
        checkScalar(value);
        return value;
    }

    private static byte[] fromDer(byte[] sig) throws GeneralSecurityException {
        ByteBuffer in = ByteBuffer.wrap(sig);
        if (sig.length < 8 || in.get() != 0x30 || (in.get() & 255) != in.remaining()) {
            throw new SignatureException("ECDSA DER 序列无效");
        }
        BigInteger r = derInteger(in);
        BigInteger s = derInteger(in);
        if (in.hasRemaining()) {
            throw new SignatureException("ECDSA DER 签名带有多余字节");
        }
        byte[] out = new byte[64];
        putUnsigned(r, out, 0);
        putUnsigned(s.min(ORDER.subtract(s)), out, 32);
        return out;
    }

    private static byte[] toDer(byte[] sig, int offset) throws SignatureException {
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, offset, offset + 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, offset + 32, offset + 64));
        checkScalar(r);
        checkScalar(s);
        byte[] a = r.toByteArray();
        byte[] b = s.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(4 + a.length + b.length);
        out.write(2);
        out.write(a.length);
        out.write(a, 0, a.length);
        out.write(2);
        out.write(b.length);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }
}
