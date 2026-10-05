package com.cafepos.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class UserController {

    public record CreateUser(@NotBlank String name, @NotBlank @Email String email, String mobile,
                             @NotBlank String roleCode, @NotBlank String password) {}

    public record UpdateUser(String name, String mobile, String roleCode, Boolean active) {}

    public record ResetPassword(@NotBlank String newPassword) {}

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('users.manage')")
    public List<UserService.UserDto> list() {
        return users.list();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('users.manage')")
    public List<UserService.RoleDto> roles() {
        return users.roles();
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('users.manage')")
    public UserService.UserDto create(@Valid @RequestBody CreateUser r) {
        return users.create(r.name(), r.email(), r.mobile(), r.roleCode(), r.password());
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasAuthority('users.manage')")
    public UserService.UserDto update(@PathVariable long id, @RequestBody UpdateUser r) {
        return users.update(id, r.name(), r.mobile(), r.roleCode(), r.active());
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('users.manage')")
    public void reset(@PathVariable long id, @Valid @RequestBody ResetPassword r) {
        users.resetPassword(id, r.newPassword());
    }

    @PostMapping("/me/password")
    public void changeOwn(@Valid @RequestBody ChangePassword r) {
        users.changeOwnPassword(r.currentPassword(), r.newPassword());
    }
}
