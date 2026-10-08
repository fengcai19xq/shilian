package com.shilian.packager.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * 重放一致性工具：固定时间戳、固定压缩参数、固定成员顺序，保证同一 manifest 的产物字节一致。
 *
 * <p>zip 用 commons-compress 写：JDK 的 ZipOutputStream 遇到 1980-01-01 00:00:00 会追加按本地时区
 * 换算的扩展时间字段，导致不同时区下字节不同。
 */
public final class Determinism {

    /** zip 格式最早只能表示 1980-01-01，固定用它消除时间戳差异（与 Python 版一致）。 */
    public static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

    /**
     * 写入时先用次日：commons-compress 的 1980 下界是按类加载时的时区算的，FIXED_TIME 换算后
     * 可能落到下界之前而追加扩展时间字段；写完再由 {@link #patchDosTimes} 改回 FIXED_TIME。
     */
    private static final LocalDateTime WRITE_TIME = FIXED_TIME.plusDays(1);

    private static final short DOS_TIME = (short) ((FIXED_TIME.getHour() << 11)
            | (FIXED_TIME.getMinute() << 5) | (FIXED_TIME.getSecond() / 2));
    private static final short DOS_DATE = (short) (((FIXED_TIME.getYear() - 1980) << 9)
            | (FIXED_TIME.getMonthValue() << 5) | FIXED_TIME.getDayOfMonth());

    public static final Date FIXED_DATE = Date.from(FIXED_TIME.toInstant(ZoneOffset.UTC));

    private static final int FILE_MODE = 0100644;

    private Determinism() {}

    public static ZipArchiveOutputStream newZip(ByteArrayOutputStream buf) {
        ZipArchiveOutputStream zos = new ZipArchiveOutputStream(buf);
        zos.setEncoding("UTF-8");
        zos.setUseLanguageEncodingFlag(true);
        zos.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);
        zos.setMethod(ZipArchiveOutputStream.DEFLATED);
        zos.setLevel(6);
        return zos;
    }

    /** 写入一个成员，不带扩展时间字段；DOS 时间在 {@link #patchDosTimes} 中统一定为 FIXED_TIME。 */
    public static void putEntry(ZipArchiveOutputStream zos, String name, byte[] data)
            throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setTime(WRITE_TIME.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        entry.setUnixMode(FILE_MODE);
        entry.setSize(data.length);
        zos.putArchiveEntry(entry);
        zos.write(data);
        zos.closeArchiveEntry();
    }

    /** 把任意 zip 容器（如 POI 生成的 xlsx）按原成员顺序重打包，抹掉时间戳与压缩差异。 */
    public static byte[] normalizeZip(byte[] zip) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(zip.length);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
                ZipArchiveOutputStream out = newZip(buf)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    putEntry(out, e.getName(), in.readAllBytes());
                }
            }
            out.finish();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return patchDosTimes(buf.toByteArray());
    }

    /** 直接改写中央目录与本地文件头里的 DOS 时间/日期字段，结果与运行时区无关。 */
    public static byte[] patchDosTimes(byte[] zip) {
        ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = zip.length - 22; i >= 0; i--) {
            if (b.getInt(i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IllegalStateException("zip 缺少中央目录结束记录");
        }
        int count = Short.toUnsignedInt(b.getShort(eocd + 10));
        int cd = b.getInt(eocd + 16);
        for (int n = 0; n < count; n++) {
            if (b.getInt(cd) != 0x02014b50) {
                throw new IllegalStateException("zip 中央目录损坏");
            }
            b.putShort(cd + 12, DOS_TIME).putShort(cd + 14, DOS_DATE);
            int local = b.getInt(cd + 42);
            if (b.getInt(local) != 0x04034b50) {
                throw new IllegalStateException("zip 本地文件头损坏");
            }
            b.putShort(local + 10, DOS_TIME).putShort(local + 12, DOS_DATE);
            cd += 46 + Short.toUnsignedInt(b.getShort(cd + 28))
                    + Short.toUnsignedInt(b.getShort(cd + 30))
                    + Short.toUnsignedInt(b.getShort(cd + 32));
        }
        return zip;
    }
}
