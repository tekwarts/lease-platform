package com.example.leaseplatform.exception;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "Resource not found or inaccessible");
    }
}
