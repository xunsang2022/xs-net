package com.game.po.player.user;

import com.game.po.base.Po;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.sql.Timestamp;

@EqualsAndHashCode(callSuper = true)
@ToString
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User extends Po {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", unique = true)
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "online")
    private Integer online = 0;

    @Column(name = "last_login_at")
    private Timestamp lastLoginAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;
}
