package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** R2 预签名 URL 生成（docs/attachment-storage.md §7.3）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class R2PresignService {

    private final R2ClientFactory clients;
    private final StorageProperties properties;

    public String presign(String storageKey) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(clients.bucket())
                .key(clients.objectKey(storageKey))
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(properties.getR2().getPresignTtl())
                .getObjectRequest(objectRequest)
                .build();
        try {
            return clients.presigner().presignGetObject(presignRequest).url().toString();
        } catch (RuntimeException ex) {
            log.warn("R2 预签名失败：key={}", storageKey, ex);
            throw ApiException.serviceUnavailable("对象存储签名失败");
        }
    }
}
