package dev.jnz.backend;

import dev.jnz.frontend.Program;
import dev.jnz.frontend.Linkage;
import org.objectweb.asm.Type;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/** 每个原生库独立生成 VM 的密钥和操作码置换。 */
final class EncryptedPrograms {
    final byte[] key = new byte[32];
    final byte[] decode = new byte[256];
    private final byte[] encode = new byte[256];
    private final byte[] noncePrefix = new byte[8];
    private final SecureRandom random;

    EncryptedPrograms(SecureRandom random) {
        this.random = random;
        random.nextBytes(key);
        random.nextBytes(noncePrefix);
        for (int i = 0; i < encode.length; i++) encode[i] = (byte) i;
        for (int i = encode.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            byte tmp = encode[i];
            encode[i] = encode[j];
            encode[j] = tmp;
        }
        for (int i = 0; i < encode.length; i++) decode[encode[i] & 255] = (byte) i;
    }

    void write(Program p, Path directory) throws IOException {
        if (p.id < 0 || p.code.length == 0 || p.code.length % 3 != 0) {
            throw new IOException("Invalid VM instruction stream for method " + p.id);
        }
        int[] instructions = p.handlers.length == 0 ? VmLayout.shuffle(p.code, random) : p.code;
        ByteBuffer code = littleEndian((instructions.length / 3) * 9);
        for (int i = 0; i < instructions.length; i += 3) {
            int opcode = instructions[i];
            if (opcode < 0 || opcode > 255) throw new IOException("Invalid VM opcode " + opcode);
            code.put(encode[opcode]).putInt(instructions[i + 1]).putInt(instructions[i + 2]);
        }
        byte[] nonce = littleEndian(12).put(noncePrefix).putInt(p.id).array();
        byte[] aad = littleEndian(12).putInt(p.id).putInt(p.maxLocals).putInt(p.maxStack).array();
        seal(code.array(), nonce, aad, directory.resolve("p" + p.id + ".bin"));
    }

    void writeNames(List<Program> methods, Path directory) throws IOException {
        int id = methods.get(0).id;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Program p : methods) {
            for (String text : Arrays.asList(p.name, p.desc)) {
                writeJniString(bytes, text);
            }
        }
        byte[] nonce = littleEndian(12).put(noncePrefix).putInt(id).array();
        nonce[0] ^= (byte) 0x80; // separate metadata nonce domain
        byte[] aad = littleEndian(8).putInt(id).putInt(methods.size()).array();
        seal(bytes.toByteArray(), nonce, aad, directory.resolve("n" + id + ".bin"));
    }

    void writeLinks(Program p, Path directory) throws IOException {
        int count = p.links.size();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        ByteBuffer index = littleEndian(4 + count * 4).putInt(count);
        writeJniString(payload, p.owner);
        for (Linkage link : p.links) {
            index.putInt(index.capacity() + payload.size());
            writeJniString(payload, link.name);
            writeJniString(payload, link.descriptor);
            Type[] params = link.argumentTypes();
            payload.write(link.mode);
            payload.write(params.length);
            payload.write(kind(link.resultType()));
            for (Type param : params) payload.write(kind(param));
        }
        byte[] nonce = littleEndian(12).put(noncePrefix).putInt(p.id).array();
        nonce[0] ^= (byte) 0x40;
        byte[] body = littleEndian(index.capacity() + payload.size()).put(index.array()).put(payload.toByteArray()).array();
        seal(body, nonce, littleEndian(4).putInt(p.id).array(), directory.resolve("g" + p.id + ".bin"));
    }

    void writeHandlers(Program p, Path directory) throws IOException {
        ByteBuffer table = littleEndian(p.handlers.length * 4);
        for (int value : p.handlers) table.putInt(value);
        byte[] nonce = littleEndian(12).put(noncePrefix).putInt(p.id).array();
        nonce[0] ^= (byte) 0x20;
        seal(table.array(), nonce, littleEndian(4).putInt(p.id).array(), directory.resolve("h" + p.id + ".bin"));
    }

    private static int kind(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? 'L' : type.getDescriptor().charAt(0);
    }

    private static void writeJniString(ByteArrayOutputStream out, String value) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        new DataOutputStream(encoded).writeUTF(value);
        byte[] modified = encoded.toByteArray();
        out.write(modified, 2, modified.length - 2);
        out.write(0);
    }

    private void seal(byte[] plain, byte[] nonce, byte[] aad, Path file) throws IOException {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(true, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] blob = Arrays.copyOf(nonce, 12 + cipher.getOutputSize(plain.length));
        try {
            int written = cipher.processBytes(plain, 0, plain.length, blob, 12);
            cipher.doFinal(blob, 12 + written);
        } catch (InvalidCipherTextException e) {
            throw new IOException("Cannot encrypt " + file.getFileName(), e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        Files.write(file, blob);
    }

    private static ByteBuffer littleEndian(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
