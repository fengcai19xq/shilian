package com.shilian.packager.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Optional;

/**
 * 执行结果。签名 URL 由调用方按 storage 实现自行换取。
 *
 * <p>红线：资料包生成后仍需人工终审并登记外发，系统不自动对外发送，故 requires_manual_review 恒为 true。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PackageResult(
        String packageId,
        List<Artifact> artifacts,
        List<String> missingEntries,
        boolean requiresManualReview) {

    public PackageResult {
        artifacts = List.copyOf(artifacts);
        missingEntries = List.copyOf(missingEntries);
    }

    public Optional<Artifact> artifact(OutputKind kind) {
        return artifacts.stream().filter(a -> a.kind() == kind).findFirst();
    }
}
