package com.echolint.repository;

import com.echolint.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** 鉴权：按明文 SHA-256 查（唯一索引），不做字符串比较 */
    Optional<ApiKey> findByKeyHash(String keyHash);

    /** 列表：最新创建的在前 */
    List<ApiKey> findAllByOrderByCreatedAtDescIdDesc();
}
