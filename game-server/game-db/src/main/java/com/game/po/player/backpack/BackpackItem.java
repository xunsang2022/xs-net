package com.game.po.player.backpack;

import com.game.po.base.Po;
import jakarta.persistence.*;
import lombok.*;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "backpack_items", indexes = @Index(columnList = "player_id"))
public class BackpackItem extends Po {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "count")
    private Long count;
}
