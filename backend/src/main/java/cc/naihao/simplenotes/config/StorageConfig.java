package cc.naihao.simplenotes.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 存储配置绑定入口。 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
}
