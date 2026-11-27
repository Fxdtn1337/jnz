package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;

final class ModuleTransfer {
    private ModuleTransfer() {}

    static void verifiedCopy(ModuleSource module, Path output) throws IOException {
        ModuleIndex index = module.index;
        ModuleBudget budget = new ModuleBudget(index.length);
        MessageDigest digest = DigestCodec.sha256();
        MessageDigest stored = DigestCodec.sha256();
        byte[] plainBuffer = new byte[16_384];
        byte[] compressedBuffer = new byte[16_384];
        try (OutputStream out = Files.newOutputStream(output)) {
            for (int part = 0; part < index.blocks; part++) {
                try (InputStream in = module.chunk(part)) {
                    DataInputStream framed = new DataInputStream(new DigestInputStream(in, stored));
                    ChunkFrame frame = ChunkFrame.read(framed, part);
                    budget.accept(frame.decoded);
                    frame.decode(framed, out, digest, plainBuffer, compressedBuffer);
                } catch (EOFException truncated) {
                    throw new IOException("模块分片截断 " + module.label() + "/" + part, truncated);
                }
            }
        }
        if (!budget.complete() || !MessageDigest.isEqual(index.digest, digest.digest()))
            throw new IOException("模块 SHA-256 或长度校验失败 " + module.label());
        if (!MessageDigest.isEqual(index.storedDigest, stored.digest()))
            throw new IOException("模块存储帧 SHA-256 校验失败 " + module.label());
    }
}
