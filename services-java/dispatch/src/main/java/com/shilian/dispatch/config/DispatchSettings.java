package com.shilian.dispatch.config;

/** 运行参数：超时小时数、卡片上传链接前缀。 */
public record DispatchSettings(double timeoutHours, String uploadUrlBase) {

    public DispatchSettings {
        if (!(timeoutHours > 0)) {
            throw new IllegalArgumentException("dispatch.timeout-hours 必须大于 0");
        }
        uploadUrlBase = uploadUrlBase == null ? "" : uploadUrlBase.replaceAll("/+$", "");
    }
}
