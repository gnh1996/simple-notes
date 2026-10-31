package cc.naihao.simplenotes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.naihao.simplenotes.common.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SyncCursorTest {

    @Test
    void roundTripsTimeAndId() {
        Instant time = Instant.parse("2026-10-31T08:12:34.123456Z");
        SyncCursor cursor = new SyncCursor(time, "6f1d3a8e-1b2c-4d5e-8f90-1234567890ab");

        SyncCursor decoded = SyncCursor.decode(cursor.encode());

        assertThat(decoded).isNotNull();
        assertThat(decoded.time()).isEqualTo(time);
        assertThat(decoded.id()).isEqualTo("6f1d3a8e-1b2c-4d5e-8f90-1234567890ab");
    }

    @Test
    void blankCursorMeansFirstPage() {
        assertThat(SyncCursor.decode(null)).isNull();
        assertThat(SyncCursor.decode("")).isNull();
        assertThat(SyncCursor.decode("   ")).isNull();
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> SyncCursor.decode("not-base64!!"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("非法游标");
        assertThatThrownBy(() -> SyncCursor.decode("bm90LWEtY3Vyc29y"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("非法游标");
    }
}
