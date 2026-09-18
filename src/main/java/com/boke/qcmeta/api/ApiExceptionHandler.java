package com.boke.qcmeta.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求内容格式无效"));
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class})
    ResponseEntity<Map<String,String>> handleStoreFailure(Exception exception) {
        return ResponseEntity.status(503).body(Map.of("error","数据暂时无法保存或查询，请保留原请求编号稍后重试"));
    }
    @ExceptionHandler(com.boke.qcmeta.ycloud.YCloudClient.YCloudException.class)
    ResponseEntity<Map<String,String>> handleYCloudQuery(com.boke.qcmeta.ycloud.YCloudClient.YCloudException exception) {
        return ResponseEntity.status(502).body(Map.of("error","YCloud 查询暂未成功，本地消息状态保持不变","code",exception.code()));
    }
}
