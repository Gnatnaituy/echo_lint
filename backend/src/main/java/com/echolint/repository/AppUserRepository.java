package com.echolint.repository;

import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 用户名入库前已统一小写，这里按小写精确匹配 */
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 用于「不能禁用/降级最后一个管理员」校验 */
    long countByRoleAndEnabledTrue(UserRole role);

    /** 用于「至少保留一个可访问用户管理的启用管理员」校验（按角色 + 启用过滤，菜单在内存中求交） */
    List<AppUser> findByRoleAndEnabledTrue(UserRole role);
}
