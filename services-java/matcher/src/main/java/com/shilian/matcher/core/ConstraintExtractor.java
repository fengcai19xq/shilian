package com.shilian.matcher.core;

import com.shilian.matcher.model.Constraints;
import com.shilian.matcher.model.Scope;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从清单文本中抽取硬约束：期间 / 口径 / 份数 / 盖章。全部用正则与算术实现，<b>不调模型</b>。 */
public final class ConstraintExtractor {

    private static final Map<Character, Integer> CN_DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('一', 1), Map.entry('两', 2), Map.entry('二', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
            Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9), Map.entry('十', 10));

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern YEAR_RANGE = Pattern.compile(
            "((?:19|20)\\d{2})\\s*(?:年度?)?\\s*[-~—至到]\\s*((?:19|20)\\d{2})");
    private static final Pattern RECENT_YEARS = Pattern.compile(
            "(?:最近|近|过去)\\s*([0-9一二两三四五六七八九十]+)\\s*(?:个)?年");
    private static final Pattern COPIES = Pattern.compile(
            "(?:一式)?\\s*([0-9一二两三四五六七八九十]+)\\s*(?:份|套|本|册)");
    private static final Pattern STAMP = Pattern.compile("盖章|公章|骑缝章|加盖|签章");
    private static final Pattern CONSOLIDATED = Pattern.compile("合并");
    private static final Pattern STANDALONE = Pattern.compile("单体|母公司|本部|个别报表");

    private ConstraintExtractor() {
    }

    static Integer toInt(String raw) {
        String token = raw.strip();
        if (!token.isEmpty() && token.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (token.equals("十")) {
            return 10;
        }
        if (token.length() == 2 && token.charAt(0) == '十') { // 十一 ~ 十九
            return 10 + CN_DIGITS.getOrDefault(token.charAt(1), 0);
        }
        if (token.length() == 2 && token.charAt(1) == '十') { // 二十 ~ 九十
            return CN_DIGITS.getOrDefault(token.charAt(0), 0) * 10;
        }
        if (token.length() == 1) {
            return CN_DIGITS.get(token.charAt(0));
        }
        return null;
    }

    /** 抽取期间年份，返回升序去重的年份字符串列表。 */
    public static List<String> extractPeriods(String text, Integer referenceYear) {
        int ref = referenceYear != null && referenceYear != 0
                ? referenceYear : LocalDate.now(ZoneOffset.UTC).getYear();
        TreeSet<String> years = new TreeSet<>();

        Matcher range = YEAR_RANGE.matcher(text);
        while (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            if (a <= b && b - a < 20) {
                for (int y = a; y <= b; y++) {
                    years.add(String.valueOf(y));
                }
            }
        }

        if (years.isEmpty()) {
            Matcher recent = RECENT_YEARS.matcher(text);
            if (recent.find()) {
                Integer n = toInt(recent.group(1));
                if (n != null && n > 0 && n <= 20) {
                    for (int y = ref - n + 1; y <= ref; y++) {
                        years.add(String.valueOf(y));
                    }
                }
            }
        }

        if (years.isEmpty()) {
            Matcher year = YEAR.matcher(text);
            while (year.find()) {
                years.add(year.group());
            }
        }
        return List.copyOf(years);
    }

    public static List<String> extractPeriods(String text) {
        return extractPeriods(text, null);
    }

    public static Scope extractScope(String text) {
        if (CONSOLIDATED.matcher(text).find()) {
            return Scope.CONSOLIDATED;
        }
        if (STANDALONE.matcher(text).find()) {
            return Scope.STANDALONE;
        }
        return null;
    }

    public static Integer extractCopies(String text) {
        Matcher m = COPIES.matcher(text);
        if (!m.find()) {
            return null;
        }
        Integer n = toInt(m.group(1));
        return n != null && n > 0 ? n : null;
    }

    public static boolean extractStampRequired(String text) {
        return STAMP.matcher(text).find();
    }

    public static Constraints extractConstraints(String text, Integer referenceYear) {
        return new Constraints(extractPeriods(text, referenceYear), extractScope(text),
                extractCopies(text), extractStampRequired(text));
    }

    public static Constraints extractConstraints(String text) {
        return extractConstraints(text, null);
    }
}
