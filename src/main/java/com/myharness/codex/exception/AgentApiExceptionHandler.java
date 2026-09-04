package com.myharness.codex.exception;

import com.myharness.codex.controller.AgentController;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.vo.ApiResponseVO;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=AgentController.class)
public class AgentApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseVO<Void>> validation(MethodArgumentNotValidException exception) {
        String info=exception.getBindingResult().getFieldErrors().stream().map(FieldError::getDefaultMessage)
                .distinct().collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(ApiResponseVO.<Void>error(ErrorCode.AGENT_INVALID_REQUEST.getCode(),info));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseVO<Void>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponseVO.<Void>error(ErrorCode.AGENT_INVALID_REQUEST.getCode(),"请求体格式不正确"));
    }
}
