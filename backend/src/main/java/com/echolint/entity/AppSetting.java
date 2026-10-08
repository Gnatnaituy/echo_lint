package com.echolint.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 运行时配置项（键值对）。目前用于持久化 AI 复筛所用的供应商与模型，
 * 使「系统设置」页的切换无需重启即可生效，并在重启后保持。
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
public class AppSetting {

    @Id
    @Column(name = "setting_key", nullable = false, length = 64)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 255)
    private String settingValue;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static AppSetting of(String key, String value) {
        AppSetting s = new AppSetting();
        s.setSettingKey(key);
        s.setSettingValue(value == null ? "" : value);
        return s;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
