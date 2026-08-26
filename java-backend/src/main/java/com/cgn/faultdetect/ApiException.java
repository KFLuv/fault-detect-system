package com.cgn.faultdetect;

/** 业务异常：携带 HTTP 状态码与错误详情（对应 Python 版 HTTPException） */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
