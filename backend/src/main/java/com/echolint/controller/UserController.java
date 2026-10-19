package com.echolint.controller;

import com.echolint.dto.UserCreateRequest;
import com.echolint.dto.UserUpdateRequest;
import com.echolint.dto.UserView;
import com.echolint.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理（仅 ADMIN，权限由 SecurityConfig 的 URL 规则保证）。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<UserView> list() {
        return userService.list();
    }

    @PostMapping
    public ResponseEntity<UserView> create(@Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return userService.update(id, request);
    }

    /** 禁用（不物理删除，保留审计/复核历史引用） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(@PathVariable Long id) {
        userService.disable(id);
        return ResponseEntity.noContent().build();
    }
}
