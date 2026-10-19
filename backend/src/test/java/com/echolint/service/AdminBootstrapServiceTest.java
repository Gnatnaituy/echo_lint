package com.echolint.service;

import com.echolint.config.AppProperties;
import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import com.echolint.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 首次启动引导管理员：仅用户表为空时创建、用户名规范化、BCrypt 哈希、默认账号密码。
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    @Mock
    private AppUserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AppProperties appProperties;
    private AdminBootstrapService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        service = new AdminBootstrapService(userRepository, passwordEncoder, appProperties);
    }

    private AppUser runAndCapture() {
        service.run(null);
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createsDefaultAdminWhenUserTableEmpty() {
        when(userRepository.count()).thenReturn(0L);

        AppUser admin = runAndCapture();

        assertEquals("admin", admin.getUsername());
        assertEquals("管理员", admin.getDisplayName());
        assertEquals(UserRole.ADMIN, admin.getRole());
        assertTrue(admin.isEnabled());
        assertTrue(passwordEncoder.matches("admin12345", admin.getPasswordHash()));
        assertNotEquals("admin12345", admin.getPasswordHash(), "密码必须以 BCrypt 哈希入库");
    }

    @Test
    void doesNothingWhenUsersAlreadyExist() {
        when(userRepository.count()).thenReturn(1L);

        service.run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void usesConfiguredCredentialsAndNormalizesUsername() {
        when(userRepository.count()).thenReturn(0L);
        appProperties.getAdmin().setUsername("  Root ");
        appProperties.getAdmin().setPassword("S3cretPass");

        AppUser admin = runAndCapture();

        assertEquals("root", admin.getUsername());
        assertTrue(passwordEncoder.matches("S3cretPass", admin.getPasswordHash()));
    }
}
