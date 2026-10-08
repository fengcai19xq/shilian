package com.shilian.wecomsync.identity;

/** 手机号脱敏：保留前 3 位与后 4 位，中间固定 4 个星号；位数不足 7 位时整体打码。 */
public final class MobileMasker {

    private MobileMasker() {}

    public static String mask(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return null;
        }
        String digits = mobile.replaceAll("\\D", "");
        if (digits.startsWith("86") && digits.length() == 13) {
            digits = digits.substring(2);
        }
        if (digits.length() < 7) {
            return "****";
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }
}
