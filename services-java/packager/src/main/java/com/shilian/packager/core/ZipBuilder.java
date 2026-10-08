package com.shilian.packager.core;

import com.shilian.packager.model.Entry;
import com.shilian.packager.model.FileRef;
import com.shilian.packager.model.Manifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * zip 产物：按清单编号建目录，文件名 {@code 编号_标准名称_期间.pdf}。
 *
 * <p>为保证重放一致，所有成员使用固定时间戳与固定压缩参数，成员顺序按 manifest 顺序。
 */
public final class ZipBuilder {

    private ZipBuilder() {}

    /** 条目编号 → zip 内路径列表；missing 与无文件条目不出现。 */
    public static Map<String, List<String>> members(Manifest manifest) {
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (Entry entry : manifest.entries()) {
            if (!entry.isPackable()) {
                continue;
            }
            String folder = Naming.dirName(entry);
            List<FileRef> files = entry.orderedFiles();
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                paths.add(folder + "/" + Naming.fileName(entry, files.get(i), i, files.size()));
            }
            members.put(entry.no(), paths);
        }
        return members;
    }

    public static byte[] build(Manifest manifest, Map<String, byte[]> sources) {
        Map<String, List<String>> members = members(manifest);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = Determinism.newZip(buf)) {
            for (Entry entry : manifest.entries()) {
                List<String> paths = members.get(entry.no());
                if (paths == null) {
                    continue;
                }
                List<FileRef> files = entry.orderedFiles();
                for (int i = 0; i < files.size(); i++) {
                    FileRef ref = files.get(i);
                    byte[] data = PdfSupport.subset(ref.uri(), sources.get(ref.uri()), ref);
                    Determinism.putEntry(zos, paths.get(i), data);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Determinism.patchDosTimes(buf.toByteArray());
    }
}
