package com.echolint.service;

import com.echolint.dfa.DfaHit;
import com.echolint.dfa.DfaMatcher;
import com.echolint.repository.DictionaryWordRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DFA 初筛服务：从词典加载启用词构建匹配器，词典变更后自动重建。
 * 说明：@PostConstruct 阶段 data.sql 尚未执行（defer-datasource-initialization），
 * 因此到 ApplicationReadyEvent 再重建一次，保证预置词库生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DfaService implements ApplicationListener<ApplicationReadyEvent> {

    private final DictionaryWordRepository dictionaryWordRepository;

    private volatile DfaMatcher matcher = new DfaMatcher(List.of());

    @PostConstruct
    void init() {
        rebuild();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        rebuild();
        log.info("DFA 词典就绪（预置词库已加载），启用词 {} 个", matcher.wordCount());
    }

    public synchronized void rebuild() {
        List<String> words = dictionaryWordRepository.findByEnabledTrueOrderByWordAsc()
                .stream().map(w -> w.getWord()).toList();
        this.matcher = new DfaMatcher(words);
        log.info("DFA 匹配器已重建，启用词 {} 个", words.size());
    }

    /**
     * 初筛：返回全部命中（词 + 原文区间）
     */
    public List<DfaHit> match(String text) {
        return matcher.match(text);
    }

    public int activeWordCount() {
        return matcher.wordCount();
    }
}