package com.game.eventsourcing;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import com.xs.kernel.model.XsModel;
import com.xs.kernel.model.XsModelSupport;

import java.lang.reflect.Field;

/** MyBatis-Plus ActiveRecord base with xs-net's persistence-agnostic model API. */
public abstract class GameModel<M extends GameModel<M>> extends Model<M> implements XsModel<M> {

    private static final XsModelSupport.FieldMapper FIELD_MAPPER = new XsModelSupport.FieldMapper() {
        @Override
        public boolean isPersistentField(Field field) {
            if (field.getDeclaringClass() == GameModel.class || field.getDeclaringClass() == Model.class) {
                return false;
            }
            TableField tableField = field.getAnnotation(TableField.class);
            return tableField == null || tableField.exist();
        }

        @Override
        public String columnName(Field field) {
            TableId tableId = field.getAnnotation(TableId.class);
            if (tableId != null && !tableId.value().isBlank()) {
                return tableId.value();
            }
            TableField tableField = field.getAnnotation(TableField.class);
            if (tableField != null && !tableField.value().isBlank()) {
                return tableField.value();
            }
            return field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        }
    };

    private final XsModelSupport xsModelSupport =
            new XsModelSupport(this, FIELD_MAPPER, this::beforePropertySet);

    @Override
    public XsModelSupport xsModelSupport() {
        return xsModelSupport;
    }

    protected void beforePropertySet(String column) {
        // Defaults to no persistence-specific action.
    }
}
