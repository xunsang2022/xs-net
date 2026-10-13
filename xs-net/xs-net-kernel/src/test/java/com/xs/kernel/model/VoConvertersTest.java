package com.xs.kernel.model;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VoConvertersTest {

    /** 近似协议记录的目标类型。 */
    private record SampleVo(String name) {
    }

    /** 近似模型对象，用于验证 XsModel 默认 toVo()。 */
    private static final class SampleModel implements XsModel<SampleModel> {
        private final XsModelSupport support = new XsModelSupport(this, new XsModelSupport.FieldMapper() {
            @Override
            public boolean isPersistentField(Field field) {
                return true;
            }

            @Override
            public String columnName(Field field) {
                return field.getName();
            }
        }, ignored -> {
        });

        @Override
        public XsModelSupport xsModelSupport() {
            return support;
        }
    }

    @Test
    public void convertsRegisteredModelToVo() {
        VoConverters.register(SampleModel.class, SampleVo.class, source -> new SampleVo("alice"));

        assertEquals(new SampleVo("alice"), VoConverters.convert(new SampleModel()));
    }

    @Test
    public void modelDefaultMethodConvertsThroughRegistry() {
        VoConverters.register(SampleModel.class, SampleVo.class, source -> new SampleVo("bob"));

        SampleVo vo = new SampleModel().toVo();

        assertEquals(new SampleVo("bob"), vo);
    }

    @Test
    public void rejectsModelWithoutConverter() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> VoConverters.convert(new Object()));

        assertTrue(failure.getMessage().contains("No protocol converter registered"));
    }
}
