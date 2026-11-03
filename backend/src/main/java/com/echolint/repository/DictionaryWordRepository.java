package com.echolint.repository;

import com.echolint.entity.DictionaryWord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DictionaryWordRepository
        extends JpaRepository<DictionaryWord, Long>, JpaSpecificationExecutor<DictionaryWord> {

    List<DictionaryWord> findByEnabledTrueOrderByWordAsc();

    Optional<DictionaryWord> findByWordIgnoreCase(String word);

    /**
     * 原子累加命中次数（审计 P2-3）：流水线先按词聚合，再用本方法对每个不同词做一条 UPDATE， 替代原先「逐命中 find + save」的 N+1 读-改-写（并发时会丢计数）。
     *
     * <p>word 是 {@code WordNormalizer} 规范化后的词典词（小写），与库内 {@code word} 精确相等， 可命中 {@code
     * uk_dictionary_word} 唯一索引。方法自带小事务，调用方无需包事务。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "update DictionaryWord d set d.hitCount = d.hitCount + :delta, d.updatedAt = :now "
                    + "where d.word = :word")
    int addHitCount(
            @Param("word") String word, @Param("delta") int delta, @Param("now") LocalDateTime now);

    @Query("select count(d) from DictionaryWord d where d.source = 'MINED' and d.enabled = false")
    long countMinedPending();

    long countByEnabledTrue();

    long count();
}
