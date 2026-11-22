package com.voltcanary.identity.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.voltcanary.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("select user from User user where :after is null or user.id > :after order by user.id")
    List<User> reconciliationBatch(@Param("after") UUID after,
            Pageable page);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
