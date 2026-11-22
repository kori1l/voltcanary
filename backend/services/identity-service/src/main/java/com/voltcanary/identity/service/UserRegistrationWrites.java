package com.voltcanary.identity.service;

import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Completes or rolls back insertion before registration classifies a uniqueness failure. */
@Service
@RequiredArgsConstructor
public class UserRegistrationWrites {

    private final UserRepository users;

    @Transactional
    public User save(User user) {
        return users.saveAndFlush(user);
    }
}
