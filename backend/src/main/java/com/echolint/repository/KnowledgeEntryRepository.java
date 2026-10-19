package com.echolint.repository;

import com.echolint.entity.KnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long>,
        JpaSpecificationExecutor<KnowledgeEntry> {
}
