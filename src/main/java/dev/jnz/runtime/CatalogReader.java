package dev.jnz.runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class CatalogReader {
    private CatalogReader() {}

    static Map<String, byte[]> decode(byte[] bytes) throws IOException {
        Map<String, byte[]> records = new HashMap<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0x4a4e4332) throw new IOException("模块目录版本不支持");
            int count = input.readInt();
            if (count < 1 || count > ResourceLimits.CATALOG_RECORDS || count > bytes.length / 34)
                throw new IOException("模块目录条目数无效");
            for (int i = 0; i < count; i++) {
                CatalogRecord record = CatalogRecord.read(input);
                if (records.put(record.path, record.digest) != null) throw new IOException("模块目录重复条目");
            }
            BinaryStream.end(input, ResourcePaths.catalog());
        }
        return records;
    }
}
