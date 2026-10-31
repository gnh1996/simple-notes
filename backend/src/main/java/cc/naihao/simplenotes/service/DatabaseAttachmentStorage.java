package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.StorageProperties;
import cc.naihao.simplenotes.domain.StorageMode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库附件存储适配器（docs/attachment-storage.md §4.2 / §5.2）。
 *
 * <p>这里刻意使用 JdbcTemplate + 流式 API，而不是 JPA {@code byte[]} / {@code @Lob}：
 * 20 MB 文件不应该在 Hibernate 里整块物化、复制。读取时持有连接直到响应流关闭。
 */
@Component
@RequiredArgsConstructor
public class DatabaseAttachmentStorage implements AttachmentStorage {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final StorageProperties properties;

    @Override
    public StorageMode mode() {
        return StorageMode.DATABASE;
    }

    @Override
    public void store(String storageKey, InputStream content, long size, FileMeta meta) {
        long maxBytes = properties.getDatabase().getMaxBytes();
        if (size > maxBytes) {
            throw ApiException.badRequest("数据库模式单文件不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into attachment_blob (storage_key, content, create_time) values (?, ?, ?)");
            statement.setString(1, storageKey);
            statement.setBinaryStream(2, content, size);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            return statement;
        });
    }

    @Override
    public StoredObject load(String storageKey) {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            connection = dataSource.getConnection();
            statement = connection.prepareStatement(
                    "select content, octet_length(content) from attachment_blob where storage_key = ?");
            statement.setString(1, storageKey);
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                closeQuietly(resultSet, statement, connection);
                throw ApiException.notFound("附件文件不存在");
            }
            InputStream raw = resultSet.getBinaryStream(1);
            if (raw == null) {
                closeQuietly(resultSet, statement, connection);
                throw ApiException.notFound("附件文件不存在");
            }
            long size = resultSet.getLong(2);
            ResultSet heldResultSet = resultSet;
            PreparedStatement heldStatement = statement;
            Connection heldConnection = connection;
            return new StoredObject(new FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        closeQuietly(heldResultSet, heldStatement, heldConnection);
                    }
                }
            }, size, null, null);
        } catch (SQLException ex) {
            closeQuietly(resultSet, statement, connection);
            throw ApiException.serviceUnavailable("数据库附件读取失败");
        }
    }

    @Override
    public void delete(String storageKey) {
        jdbcTemplate.update("delete from attachment_blob where storage_key = ?", storageKey);
    }

    @Override
    public void validate() {
        jdbcTemplate.queryForObject("select count(*) from attachment_blob", Long.class);
    }

    private static void closeQuietly(ResultSet resultSet, PreparedStatement statement, Connection connection) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException ignored) {
                // 清理阶段忽略
            }
        }
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                // 清理阶段忽略
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 清理阶段忽略
            }
        }
    }
}
