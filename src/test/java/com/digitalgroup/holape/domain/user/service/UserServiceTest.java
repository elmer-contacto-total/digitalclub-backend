package com.digitalgroup.holape.domain.user.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private User testUser;
    private Client testClient;

    @BeforeEach
    void setUp() {
        testClient = new Client();
        testClient.setId(1L);
        testClient.setName("Test Client");

        testUser = new User();
        testUser.setId(1L);
        testUser.setPhone("51999999999");
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEmail("test@example.com");
        testUser.setClient(testClient);
        testUser.setRole(UserRole.AGENT);
    }

    @Test
    void findById_ExistingUser_ReturnsUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        User result = userService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Test", result.getFirstName());
    }

    @Test
    void findById_NonExistingUser_ThrowsException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.findById(99L));
    }

    @Test
    void findByPhone_ExistingUser_ReturnsUser() {
        when(userRepository.findByPhone("51999999999")).thenReturn(Optional.of(testUser));

        User result = userService.findByPhone("51999999999");

        assertNotNull(result);
        assertEquals("51999999999", result.getPhone());
    }

    @Test
    void findByEmail_ExistingUser_ReturnsUser() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        User result = userService.findByEmail("test@example.com");

        assertNotNull(result);
        assertEquals("test@example.com", result.getEmail());
    }

    @Test
    void changePassword_ValidPassword_UpdatesPassword() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        userService.changePassword(1L, "newPassword");

        verify(passwordEncoder).encode("newPassword");
        verify(userRepository).save(testUser);
    }

    @Test
    void getFullName_WithFirstAndLastName_ReturnsCombined() {
        assertEquals("Test User", testUser.getFullName());
    }

    @Test
    void isAdmin_AdminRole_ReturnsTrue() {
        testUser.setRole(UserRole.ADMIN);
        assertTrue(testUser.getRole().isAdmin());
    }

    @Test
    void isAgent_AgentRole_ReturnsTrue() {
        testUser.setRole(UserRole.AGENT);
        assertTrue(testUser.getRole().isAgent());
    }
}
