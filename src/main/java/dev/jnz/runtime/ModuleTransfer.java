package dev.jnz.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

final class ModuleTransfer {
    private ModuleTransfer() {}

    static void verifiedCopy(ModuleSource module, Path output) throws IOException {
        ModuleIndex index = module.index;
        ModuleBudget budget = new ModuleBudget(index.length);
        MessageDigest digest = DigestCodec.sha256();
        byte[] buffer = new byte[16_384];
        try (OutputStream out = Files.newOutputStream(output)) {
            for (int part = 0; part < index.blocks; part++) {
                try (InputStream in = module.chunk(part)) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        budget.accept(count);
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                    }
                }
            }
        }
        if (!budget.complete() || !MessageDigest.isEqual(index.digest, digest.digest()))
            throw new IOException("模块 SHA-256 或长度校验失败 " + module.label());
    }
}
