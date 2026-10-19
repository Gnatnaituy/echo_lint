package com.echolint.repository;

import com.echolint.entity.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    Optional<AiProvider> findByProviderKeyIgnoreCase(String providerKey);

    boolean existsByProviderKeyIgnoreCase(String providerKey);

    /** 内置供应商在前，其余按 key 排序，保证界面顺序稳定 */
    List<AiProvider> findAllByOrderByBuiltInDescProviderKeyAsc();
}
