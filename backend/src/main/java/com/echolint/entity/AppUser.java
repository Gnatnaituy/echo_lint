package com.echolint.entity;

import com.echolint.domain.UserRole;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 系统用户（表名为 app_user，避免与数据库保留字 user 冲突）。
 *
 * <p>密码只存 BCrypt 哈希；用户名统一小写、去空格，唯一。
 */
@Entity
@Table(
        name = "app_user",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_app_user_username", columnNames = "username")
        })
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    /** BCrypt 哈希，绝不返回给前端 */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 64)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role = UserRole.VIEWER;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * 可见菜单绑定：逗号分隔的 {@link com.echolint.domain.MenuKey} 枚举名。
     *
     * <p>{@code null} / 空 = 不限制（= 角色允许的全部菜单）；非空 = 在角色允许范围内收窄。 写路径保证只存角色可用的 key，读路径对脏数据宽松（未知 key
     * 跳过）。
     */
    @Column(name = "menu_keys", length = 512)
    private String menuKeys;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (role == null) {
            role = UserRole.VIEWER;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
