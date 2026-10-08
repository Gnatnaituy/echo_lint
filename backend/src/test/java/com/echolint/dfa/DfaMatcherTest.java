package com.echolint.dfa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DfaMatcherTest {

    @Test
    void matchesCaseInsensitive() {
        DfaMatcher m = new DfaMatcher(List.of("fuck", "idiot"));
        List<DfaHit> hits = m.match("FUCK you IDIOT");
        assertEquals(2, hits.size());
        assertTrue(hits.contains(new DfaHit("fuck", 0, 4)));
        assertTrue(hits.contains(new DfaHit("idiot", 9, 14)));
    }

    @Test
    void matchesSeparatorEvasion() {
        DfaMatcher m = new DfaMatcher(List.of("fuck"));
        List<DfaHit> hits = m.match("you are f u c k ing stupid");
        assertTrue(hits.contains(new DfaHit("fuck", 8, 15)));
    }

    @Test
    void matchesPunctuationEvasion() {
        DfaMatcher m = new DfaMatcher(List.of("fuck"));
        // 字母间插入标点（字符跳变），全部字母都在
        List<DfaHit> hits = m.match("what the f.u.c.k!");
        assertEquals(1, hits.size());
        assertEquals("fuck", hits.get(0).word());
        assertEquals(9, hits.get(0).start());
        assertEquals(16, hits.get(0).end());
        // 字母缺失（如 f**k）不命中 —— 跳变只允许插入，不允许替换
        assertTrue(m.match("what the f**k!").isEmpty());
    }

    @Test
    void embeddedSubstringHit() {
        DfaMatcher m = new DfaMatcher(List.of("fuck"));
        List<DfaHit> hits = m.match("unfuckingbelievable");
        assertEquals(1, hits.size());
        assertEquals(new DfaHit("fuck", 2, 6), hits.get(0));
    }

    @Test
    void multiWordPhrase() {
        DfaMatcher m = new DfaMatcher(List.of("killyou", "shootyou"));
        List<DfaHit> hits = m.match("I will kill you, and then shoot you.");
        assertEquals(2, hits.size());
        assertTrue(hits.contains(new DfaHit("killyou", 7, 15)));
        assertTrue(hits.contains(new DfaHit("shootyou", 26, 35)));
    }

    @Test
    void noFalsePositiveOnPlainText() {
        DfaMatcher m = new DfaMatcher(List.of("fuck", "shit", "bitch"));
        List<DfaHit> hits = m.match("Thank you for calling support, how can I help you today?");
        assertTrue(hits.isEmpty());
    }

    @Test
    void dedupeOverlappingWords() {
        DfaMatcher m = new DfaMatcher(List.of("fuck", "fucking"));
        List<DfaHit> hits = m.match("fucking");
        // fuck@0-4 与 fucking@0-7 是两个不同的命中，均保留
        assertEquals(2, hits.size());
    }

    @Test
    void chineseWords() {
        DfaMatcher m = new DfaMatcher(List.of("傻逼"));
        List<DfaHit> hits = m.match("你这个 傻 逼 可不可以好好说话");
        assertEquals(1, hits.size());
        assertEquals(new DfaHit("傻逼", 4, 7), hits.get(0));
    }

    @Test
    void chineseKeywordScreening() {
        DfaMatcher m = new DfaMatcher(List.of("傻逼", "滚蛋", "找人收拾你", "短信验证码"));

        // 真实违规句：辱骂 + 驱赶
        java.util.Set<String> words = m.match("你这个人怎么这么傻逼，说了多少次了，滚蛋，别再来电话了。")
                .stream().map(DfaHit::word).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("傻逼", "滚蛋"), words);

        // 多字威胁短语整体命中
        assertEquals(List.of("找人收拾你"),
                m.match("你要是不退钱，我就找人收拾你。").stream().map(DfaHit::word).toList());
    }

    @Test
    void chineseAmbiguityIsLeftToAiScreening() {
        // 子串匹配无法区分语境：这句话命中「短信验证码」，但坐席是在做反诈提醒 ——
        // 误报由第二段 AI 复筛兜底，这正是两段式设计的意图
        DfaMatcher m = new DfaMatcher(List.of("短信验证码"));
        assertEquals(1, m.match("我们不会问您的短信验证码，那是诈骗话术。").size());

        // 正常业务用语不应命中任何词
        DfaMatcher clean = new DfaMatcher(List.of("傻逼", "滚蛋", "短信验证码", "安全账户"));
        assertTrue(clean.match("您好，您的退款会在三个工作日内原路返回，请留意银行短信。").isEmpty());
    }

    @Test
    void noHitWhenNotPresent() {
        DfaMatcher m = new DfaMatcher(List.of("kill"));
        // "kiln" 仅含 "kil"，不缺尾字母；"kinder" 同理不命中
        assertTrue(m.match("kiln").isEmpty());
        assertTrue(m.match("kinder").isEmpty());
        // 注意：子串语义意味着 "skilled" 会命中 "kill"（s-k-i-l-l），这是设计内行为
        assertEquals(1, m.match("skilled worker").size());
        DfaMatcher m2 = new DfaMatcher(List.of("compliance"));
        assertTrue(m2.match("comply").isEmpty());
    }

    @Test
    void separatorsDoNotBridgeIntoNewWordAtRoot() {
        DfaMatcher m = new DfaMatcher(List.of("fuck"));
        // 分隔符跳过发生在候选进行中；根状态下的分隔符无副作用
        List<DfaHit> hits = m.match("no fuck around here / really");
        assertTrue(hits.contains(new DfaHit("fuck", 3, 7)));
    }
}