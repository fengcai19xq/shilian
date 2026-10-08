package com.shilian.packager.core;

import com.shilian.packager.PackagingError;
import com.shilian.packager.model.FileRef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

/** PDFBox 公共操作：打开源文件、页码选择、截取、确定性保存。 */
public final class PdfSupport {

    public static final String PRODUCER = "shilian-packager";

    private PdfSupport() {}

    public static PDDocument open(String uri, byte[] data) {
        if (data == null) {
            throw new PackagingError("源文件不是可解析的 PDF：" + uri);
        }
        try {
            PDDocument doc = Loader.loadPDF(data);
            doc.getNumberOfPages();
            return doc;
        } catch (IOException | RuntimeException e) {
            throw new PackagingError("源文件不是可解析的 PDF：" + uri, e);
        }
    }

    public static int pageCount(String uri, byte[] data) {
        try (PDDocument doc = open(uri, data)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 返回要收录的 0 起算页下标；pages 为 null 时整份收录。 */
    public static List<Integer> selectedIndices(String uri, int count, FileRef ref) {
        if (ref.pages() == null) {
            return IntStream.range(0, count).boxed().toList();
        }
        List<Integer> over = ref.pages().stream().filter(p -> p > count).toList();
        if (!over.isEmpty()) {
            throw new PackagingError("页码越界：" + uri + " 共 " + count + " 页，请求 " + over);
        }
        return ref.pages().stream().map(p -> p - 1).toList();
    }

    /** 把源页面导入目标文档；资源字典浅拷贝一层，避免后续追加水印时改到共享资源。 */
    public static PDPage importPage(PDDocument target, PDPage source) throws IOException {
        PDResources resources = source.getResources();
        PDPage page = target.importPage(source);
        if (resources != null) {
            page.setResources(new PDResources(copyResources(resources.getCOSObject())));
        }
        return page;
    }

    private static COSDictionary copyResources(COSDictionary dict) {
        COSDictionary copy = new COSDictionary(dict);
        for (COSName key : new ArrayList<>(dict.keySet())) {
            COSBase value = dict.getDictionaryObject(key);
            if (value instanceof COSDictionary sub && !(value instanceof COSStream)) {
                copy.setItem(key, new COSDictionary(sub));
            }
        }
        return copy;
    }

    /** 按 pages 截取源 PDF；整份收录时原样返回，不重新编码。 */
    public static byte[] subset(String uri, byte[] data, FileRef ref) {
        if (ref.pages() == null) {
            return data;
        }
        try (PDDocument src = open(uri, data); PDDocument out = new PDDocument()) {
            for (int idx : selectedIndices(uri, src.getNumberOfPages(), ref)) {
                importPage(out, src.getPage(idx));
            }
            return save(out, null, "subset|" + uri + "|" + ref.pages());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 确定性保存：固定 Producer/Creator，不写创建时间，并用种子派生固定的 trailer /ID
     * （PDFBox 在缺 /ID 时会用当前时间生成，导致重放字节不一致）。
     */
    public static byte[] save(PDDocument doc, String title, String seed) throws IOException {
        PDDocumentInformation info = new PDDocumentInformation();
        info.setProducer(PRODUCER);
        info.setCreator(PRODUCER);
        if (title != null) {
            info.setTitle(title);
        }
        doc.setDocumentInformation(info);
        byte[] id = md5(seed);
        COSArray ids = new COSArray();
        ids.add(new COSString(id));
        ids.add(new COSString(id));
        doc.getDocument().getTrailer().setItem(COSName.ID, ids);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        doc.save(buf);
        return buf.toByteArray();
    }

    static byte[] md5(String seed) {
        try {
            return MessageDigest.getInstance("MD5").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
