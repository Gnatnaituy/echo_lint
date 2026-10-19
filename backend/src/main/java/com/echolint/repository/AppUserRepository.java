package com.echolint.repository;

import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 用户名入库前已统一小写，这里按小写精确匹配 */
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 用于「不能禁用/降级最后一个管理员」校验 */
    long countByRoleAndEnabledTrue(UserRole role);
}
