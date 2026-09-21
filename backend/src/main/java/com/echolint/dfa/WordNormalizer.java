package com.echolint.dfa;

/**
 * 词规范化：小写、仅保留字母数字与中文字符（去标点/空格/emoji）。
 * 转写文本里 "f u c k"、"f**k" 等写法即可命中词典中的 "fuck"。
 */
public final class WordNormalizer {

    private WordNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        raw.toLowerCase(java.util.Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || cp > 127) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}