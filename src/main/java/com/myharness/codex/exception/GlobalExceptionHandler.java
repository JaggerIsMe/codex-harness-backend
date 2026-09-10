package com.myharness.codex.exception;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.SendCodeVO;
import com.myharness.codex.service.mail.MailRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponseVO<Void>> handleAccessDenied() {
        return ResponseEntity.status(403).body(ApiResponseVO.error(403,"没有执行此操作的权限"));
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MailRateLimitException.class)
    public ResponseEntity<ApiResponseVO<SendCodeVO>> handleEmailRateLimit(MailRateLimitException exception) {
        long retryAfter = exception.getRetryAfterSeconds();
        ErrorCode error = exception.getErrorCode();
        return ResponseEntity.status(error.getHttpStatus()).header("Retry-After", Long.toString(retryAfter))
                .body(ApiResponseVO.error(error.getCode(), exception.getMessage(), new SendCodeVO((int)Math.min(Integer.MAX_VALUE, retryAfter))));
    }

    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponseVO<Void>> handleInvalidRequestParameter(Exception exception) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponseVO.<Void>error(errorCode.getCode(), "请求参数格式不正确"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponseVO<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponseVO.<Void>error(errorCode.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseVO<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("；"));
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponseVO.<Void>error(errorCode.getCode(), message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseVO<Void>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponseVO.<Void>error(errorCode.getCode(), "请求体格式不正确"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponseVO<Void>> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponseVO.<Void>error(errorCode.getCode(), "上传文件不能超过 20MB"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseVO<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("Unhandled server exception", exception);
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponseVO.<Void>error(errorCode.getCode(), errorCode.getMessage()));
    }
}
