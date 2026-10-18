package cc.naihao.simplenotes.security;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import cc.naihao.simplenotes.common.ApiError;
import cc.naihao.simplenotes.common.ErrorCodes;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;

/**
 * 统一 401 出口：未登录 / 会话失效 → {@code {"code":"AUTH_REQUIRED",...}}。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ApiError(ErrorCodes.AUTH_REQUIRED, "未登录或会话已失效"));
    }
}
