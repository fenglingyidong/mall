package com.mall.mcp.client;

public class MallGatewayException extends RuntimeException {

    private final int mallCode;

    public MallGatewayException(int mallCode, String message) {
        super(message);
        this.mallCode = mallCode;
    }

    public MallGatewayException(int mallCode, String message, Throwable cause) {
        super(message, cause);
        this.mallCode = mallCode;
    }

    public int mallCode() {
        return mallCode;
    }
}
