package com.shilian.packager.core;

import com.shilian.packager.model.Artifact;
import com.shilian.packager.model.Entry;
import com.shilian.packager.model.FileRef;
import com.shilian.packager.model.Manifest;
import com.shilian.packager.model.Manifests;
import com.shilian.packager.model.OutputKind;
import com.shilian.packager.model.PackageResult;
import com.shilian.packager.storage.Storage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成册执行器入口。
 *
 * <p>{@link #buildArtifacts} 是纯函数：manifest + 读文件函数 → {产物类型: 字节}，不查库、不调模型、
 * 不做匹配判断。{@link #execute} 在此之上把产物写进 storage，返回产物 uri 列表。
 */
public final class PackagingExecutor {

    private PackagingExecutor() {}

    public static String artifactFilename(Manifest manifest, OutputKind kind) {
        String pid = manifest.packageId();
        return switch (kind) {
            case ZIP -> pid + "/" + pid + ".zip";
            case MERGED_PDF -> pid + "/" + pid + "_合订本.pdf";
            case CHECKLIST_XLSX -> pid + "/" + pid + "_核对表.xlsx";
        };
    }

    /** 只读取入册条目的源文件，同一 uri 只读一次；missing 条目不触发任何读取。 */
    static Map<String, byte[]> loadSources(Manifest manifest, SourceReader read) {
        Map<String, byte[]> sources = new LinkedHashMap<>();
        for (Entry entry : manifest.entries()) {
            if (!entry.isPackable()) {
                continue;
            }
            for (FileRef ref : entry.orderedFiles()) {
                if (!sources.containsKey(ref.uri())) {
                    sources.put(ref.uri(), read.read(ref.uri()));
                }
            }
        }
        return sources;
    }

    public static Map<OutputKind, byte[]> buildArtifacts(Map<String, ?> manifest, SourceReader read) {
        return buildArtifacts(Manifests.parse(manifest), read);
    }

    /** 纯函数核心：按 manifest.outputs 顺序生成产物字节，重复的 output 只生成一次。 */
    public static Map<OutputKind, byte[]> buildArtifacts(Manifest manifest, SourceReader read) {
        Manifest m = Manifests.validate(manifest);
        Map<String, byte[]> sources = loadSources(m, read);
        Layout layout = Layout.plan(m, sources);
        Map<String, List<String>> members = ZipBuilder.members(m);

        Map<OutputKind, byte[]> out = new LinkedHashMap<>();
        Map<OutputKind, Boolean> done = new EnumMap<>(OutputKind.class);
        for (OutputKind kind : m.outputs()) {
            if (done.putIfAbsent(kind, true) != null) {
                continue;
            }
            out.put(kind, switch (kind) {
                case ZIP -> ZipBuilder.build(m, sources);
                case MERGED_PDF -> PdfBuilder.build(m, layout, sources);
                case CHECKLIST_XLSX -> XlsxBuilder.build(m, layout, members);
            });
        }
        return out;
    }

    public static PackageResult execute(Map<String, ?> manifest, Storage storage) {
        return execute(Manifests.parse(manifest), storage);
    }

    /** 执行成册并写出产物。产物仅落存储，不对外发送，须人工终审后登记外发。 */
    public static PackageResult execute(Manifest manifest, Storage storage) {
        Manifest m = Manifests.validate(manifest);
        List<String> missing = m.entries().stream().filter(Entry::isMissing).map(Entry::no).toList();
        List<Artifact> artifacts = new ArrayList<>();
        for (Map.Entry<OutputKind, byte[]> e : buildArtifacts(m, storage).entrySet()) {
            String name = artifactFilename(m, e.getKey());
            String uri = storage.write(name, e.getValue());
            artifacts.add(new Artifact(e.getKey(), name, uri, e.getValue().length));
        }
        return new PackageResult(m.packageId(), artifacts, missing, true);
    }
}
