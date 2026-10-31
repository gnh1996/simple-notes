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

    public static ApiException tagInUse(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.TAG_IN_USE, message);
    }

    public static ApiException versionConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_CONFLICT, message);
    }

    public static ApiException slugTaken(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.SLUG_TAKEN, message);
    }

    public static ApiException invalidState(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.INVALID_STATE_TRANSITION, message);
    }

    public static ApiException noChangesToPublish(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.NO_CHANGES_TO_PUBLISH, message);
    }

    public static ApiException publishValidation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PUBLISH_VALIDATION_FAILED, message);
    }

    public static ApiException permissionDenied(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, message);
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.STORAGE_UNAVAILABLE, message);
    }
}
