package com.youzheng.huicui.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

/**
 * S3 协议对象存储。**AWS S3 / 阿里云 OSS / MinIO 走的是同一套 API**，只是 endpoint 不同 ——
 * 所以这一个实现就够了，不用为每家云写一个。
 *
 * <p>阿里云 OSS 需要 {@code pathStyleAccessEnabled(false)}（它用 virtual-host 风格），
 * 而 MinIO 通常要 {@code true}。这个差异由配置 {@code path-style} 控制 ——
 * 写死任何一个都会让另一半用户的对象存不进去。
 */
public class S3BlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(S3BlobStore.class);

    private final S3Client s3;
    private final String bucket;

    public S3BlobStore(String endpoint, String region, String accessKey, String secretKey,
                       String bucket, boolean pathStyle) {
        this.bucket = bucket;
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(pathStyle);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));   // OSS / MinIO / 自建
        }
        this.s3 = builder.build();
        log.info("[Storage] 对象存储已启用：endpoint={} bucket={} pathStyle={}", endpoint, bucket, pathStyle);
    }

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .contentType(contentType == null ? "application/octet-stream" : contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public byte[] get(String key) {
        try {
            ResponseBytes<?> r = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return r.asByteArray();
        } catch (NoSuchKeyException e) {
            // 对象不存在返回 null，不抛 —— 双读回落（先对象存储、后 bytea）要靠它。
            return null;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean isExternal() {
        return true;
    }
}
