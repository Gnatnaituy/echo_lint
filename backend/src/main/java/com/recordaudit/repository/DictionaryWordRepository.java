package com.recordaudit.repository;

import com.recordaudit.entity.DictionaryWord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DictionaryWordRepository extends JpaRepository<DictionaryWord, Long>, JpaSpecificationExecutor<DictionaryWord> {

    List<DictionaryWord> findByEnabledTrueOrderByWordAsc();

    Optional<DictionaryWord> findByWordIgnoreCase(String word);

    @Query("select count(d) from DictionaryWord d where d.source = 'MINED' and d.enabled = false")
    long countMinedPending();

    long countByEnabledTrue();

    long count();
}