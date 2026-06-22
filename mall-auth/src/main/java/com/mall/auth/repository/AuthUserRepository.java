package com.mall.auth.repository;

import com.mall.auth.pojo.entity.AuthUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AuthUserRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public Optional<AuthUser> findByUsername(String username) {
        try {
            AuthUser user = jdbcTemplate.queryForObject(
                    "SELECT id, username, password FROM user WHERE username = ?",
                    (rs, rowNum) -> new AuthUser(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("password")
                    ),
                    username
            );
            return Optional.ofNullable(user);
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
