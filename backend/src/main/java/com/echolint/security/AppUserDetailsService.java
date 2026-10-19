package com.echolint.security;

import com.echolint.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 数据库用户加载（DaoAuthenticationProvider 使用）。
 */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("用户不存在");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByUsername(normalized)
                .map(AppUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }
}
