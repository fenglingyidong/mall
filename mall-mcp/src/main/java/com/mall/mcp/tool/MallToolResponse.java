package com.mall.mcp.tool;

public record MallToolResponse(boolean ok, String code, String message, Object data) {

    public static MallToolResponse ok(Object data) {
        return new MallToolResponse(true, MallToolCode.OK.name(), "success", data);
    }

    public static MallToolResponse error(MallToolCode code, String message) {
        return new MallToolResponse(false, code.name(), message == null ? code.name() : message, null);
    }
}
