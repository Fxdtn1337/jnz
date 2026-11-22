package dev.jnz.runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Map;

/** 认证路由和模块索引之后才允许读取其中的字段。 */
final class MetadataCatalog {
    private static Map<String, byte[]> records;
    private MetadataCatalog() {}

    static synchronized DataInputStream open(String name) throws IOException {
        if (records == null) {
            byte[] contents = PackedResources.bytes(ResourcePaths.catalog(), ResourceLimits.CATALOG_BYTES);
            if (!MessageDigest.isEqual(DigestCodec.hash(contents), ManifestRoot.digest()))
                throw new IOException("模块目录 SHA-256 校验失败");
            records = CatalogReader.decode(contents);
        }
        byte[] digest = records.get(name);
        if (digest == null) throw new IOException("缺少模块目录条目 " + name);
        byte[] contents = PackedResources.bytes(name, ResourceLimits.METADATA_BYTES);
        if (!MessageDigest.isEqual(DigestCodec.hash(contents), digest))
            throw new IOException("模块元数据 SHA-256 校验失败 " + name);
        return new DataInputStream(new ByteArrayInputStream(contents));
    }
}
