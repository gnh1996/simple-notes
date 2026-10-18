package cc.naihao.simplenotes.common;

import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常出口：
 *
 * <ul>
 *   <li>{@link ApiException} → 携带的 HTTP 状态 + 语义码；</li>
 *   <li>参数 / 请求体问题 → 400 {@code VALIDATION_FAILED}；</li>
 *   <li>认证 / 鉴权异常 → 原样抛回 {@code ExceptionTranslationFilter}（401 / 403 JSON 由
 *       RestAuthenticationEntryPoint / RestAccessDeniedHandler 输出，保证全链路错误体一致）；</li>
 *   <li>其余 → 500 {@code INTERNAL_ERROR}（不向客户端泄漏堆栈，仅落日志）。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describeFieldError)
                .collect(Collectors.joining("；"));
        if (message.isBlank()) {
            message = "请求参数校验失败";
        }
        return ResponseEntity.badRequest().body(new ApiError(ErrorCodes.VALIDATION_FAILED, message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleUnreadable(Exception ex) {
        return ResponseEntity.badRequest().body(new ApiError(ErrorCodes.VALIDATION_FAILED, "请求参数格式错误"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ErrorCodes.NOT_FOUND, "资源不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(Exception ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError(ErrorCodes.METHOD_NOT_ALLOWED, "请求方式不支持"));
    }

    /**
     * 方法级权限校验失败（{@code @PreAuthorize}）抛出的 {@link AccessDeniedException}。
     *
     * <p>必须原样抛回过滤器链：由 {@code ExceptionTranslationFilter} 走统一 403 出口；
     * 若在此被吞掉会落入 {@code Exception} 兜底变成 500。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void rethrowAccessDenied(AccessDeniedException ex) {
        throw ex;
    }

    /** 与上同理：认证异常原样抛回，走统一 401 出口。 */
    @ExceptionHandler(AuthenticationException.class)
    public void rethrowAuthentication(AuthenticationException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("未预期的服务端异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ErrorCodes.INTERNAL_ERROR, "服务端内部错误"));
    }

    private String describeFieldError(FieldError error) {
        String message = error.getDefaultMessage();
        return error.getField() + "：" + (message == null ? "参数不合法" : message);
    }
}
