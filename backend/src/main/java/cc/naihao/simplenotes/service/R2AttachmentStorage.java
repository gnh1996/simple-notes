package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.StorageMode;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Cloudflare R2（S3 兼容）存储适配器（docs/attachment-storage.md §5.2）。
 *
 * <p>上传 / 读取 / 删除走 S3 API；预签名由 {@link R2PresignService} 负责。这里不关心 bucket
 * 是公开还是私有，私有 bucket 的 Nginx 访问链路由部署配置决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class R2AttachmentStorage implements AttachmentStorage {

    private final R2ClientFactory clients;

    @Override
    public StorageMode mode() {
        return StorageMode.OSS;
    }

    @Override
    public void store(String storageKey, InputStream content, long size, FileMeta meta) {
        S3Client client = clients.client();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(clients.bucket())
                .key(clients.objectKey(storageKey))
                .contentType(meta.contentType())
                .contentDisposition(meta.contentDisposition())
                .cacheControl(meta.cacheControl())
                .build();
        try {
            client.putObject(request, RequestBody.fromInputStream(content, size));
        } catch (S3Exception ex) {
            log.warn("R2 上传失败：key={}, status={}", storageKey, ex.statusCode(), ex);
            throw ApiException.serviceUnavailable("对象存储暂不可用");
        }
    }

    @Override
    public StoredObject load(String storageKey) {
        S3Client client = clients.client();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(clients.bucket())
                .key(clients.objectKey(storageKey))
                .build();
        try {
            var response = client.getObject(request);
            Long length = response.response().contentLength();
            return new StoredObject(response, length == null ? 0 : length,
                    response.response().contentType(), response.response().contentDisposition());
        } catch (NoSuchKeyException ex) {
            throw ApiException.notFound("附件文件不存在");
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw ApiException.notFound("附件文件不存在");
            }
            log.warn("R2 读取失败：key={}, status={}", storageKey, ex.statusCode(), ex);
            throw ApiException.serviceUnavailable("对象存储暂不可用");
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            clients.client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(clients.bucket())
                    .key(clients.objectKey(storageKey))
                    .build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                log.warn("R2 删除失败：key={}, status={}", storageKey, ex.statusCode(), ex);
            }
        }
    }

    @Override
    public void validate() {
        clients.validateConfig();
        try {
            clients.client().headBucket(HeadBucketRequest.builder().bucket(clients.bucket()).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() == 403 || ex.statusCode() == 404) {
                throw new IllegalStateException("R2 bucket / 凭据不可用：" + ex.statusCode(), ex);
            }
            log.warn("R2 健康检查失败（启动继续）：{}", ex.getMessage());
        } catch (RuntimeException ex) {
            // 网络暂时不可达不阻塞启动；切换模式时 StorageAdminService 会硬校验。
            log.warn("R2 健康检查失败（启动继续）：{}", ex.getMessage());
        }
    }
}
