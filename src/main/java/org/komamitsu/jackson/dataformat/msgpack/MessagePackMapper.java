//
// MessagePack for Java
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package org.komamitsu.jackson.dataformat.msgpack;

import com.fasterxml.jackson.annotation.JsonFormat;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.Version;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.cfg.MapperBuilderState;
import tools.jackson.databind.module.SimpleModule;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;

public class MessagePackMapper extends ObjectMapper
{
    private static final long serialVersionUID = 3L;

    public static class Builder extends MapperBuilder<MessagePackMapper, Builder>
    {
        public Builder(MessagePackFactory f)
        {
            super(f);
            // Registered first, so a Short or Byte key serializer from a later module wins.
            addModule(new SimpleModule("msgpack-small-integer-keys")
                    .addKeySerializer(Short.class, new SmallIntegerKeySerializer())
                    .addKeySerializer(Byte.class, new SmallIntegerKeySerializer()));
        }

        protected Builder(StateImpl state)
        {
            super(state);
        }

        @Override
        public MessagePackMapper build()
        {
            return new MessagePackMapper(this);
        }

        public Builder handleBigIntegerAsString()
        {
            return withConfigOverride(BigInteger.class,
                    o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)));
        }

        public Builder handleBigDecimalAsString()
        {
            return withConfigOverride(BigDecimal.class,
                    o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)));
        }

        public Builder handleBigIntegerAndBigDecimalAsString()
        {
            return handleBigIntegerAsString().handleBigDecimalAsString();
        }

        @Override
        protected MapperBuilderState _saveState()
        {
            return new StateImpl(this);
        }

        protected static class StateImpl extends MapperBuilderState
        {
            private static final long serialVersionUID = 3L;

            public StateImpl(Builder src)
            {
                super(src);
            }

            @Override
            protected Object readResolve()
            {
                return new Builder(this).build();
            }
        }
    }

    /**
     * Writes a Short or Byte map key through {@code writePropertyId}, as Jackson already does
     * for Integer and Long keys, so all four become MessagePack integers when integer keys are
     * enabled. With them disabled the generator writes the same decimal string Jackson would.
     * Extends {@link ValueSerializer} directly because a mapper is JDK-serialized with its
     * modules, and that needs a non-serializable superclass with a no-arg constructor.
     */
    static final class SmallIntegerKeySerializer
            extends ValueSerializer<Number>
            implements Serializable
    {
        private static final long serialVersionUID = 1L;

        @Override
        public void serialize(Number value, JsonGenerator gen, SerializationContext ctxt)
        {
            gen.writePropertyId(value.longValue());
        }
    }

    public MessagePackMapper()
    {
        this(new Builder(new MessagePackFactory()));
    }

    public MessagePackMapper(MessagePackFactory f)
    {
        this(new Builder(f));
    }

    protected MessagePackMapper(Builder builder)
    {
        this(builder, new UnreadableKeyGuard());
    }

    // Every mapper, however it is built, gets its own key guard bound to itself, since the
    // guard asks this mapper's read side whether a key type can be read back. The module has
    // a fixed name, so one registered by an earlier build of the same builder is replaced.
    private MessagePackMapper(Builder builder, UnreadableKeyGuard keyGuard)
    {
        super(builder.addModule(new SimpleModule("msgpack-key-guard").setSerializerModifier(keyGuard)));
        keyGuard.bind(this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Builder rebuild()
    {
        return new Builder((Builder.StateImpl) _savedBuilderState);
    }

    public static Builder builder()
    {
        return new Builder(new MessagePackFactory());
    }

    public static Builder builder(MessagePackFactory f)
    {
        return new Builder(f);
    }

    @Override
    public Version version()
    {
        return PackageVersion.VERSION;
    }
}
