package cc.naihao.simplenotes.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 携带 HTTP 状态与错误语义码的业务异常。
 *
 * <p>由 {@link GlobalExceptionHandler} 统一转换为 {@link ApiError} 响应。
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, message);
    }

    public static ApiException permissionDenied(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, message);
    }
}
