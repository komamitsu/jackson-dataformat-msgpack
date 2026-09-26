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

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.util.ClassUtil;

/**
 * Refuses to write a map key that could not be read back. A property name is a String, so a
 * key survives only if Jackson can turn that String back into its type; for anything else
 * Jackson falls back to {@code toString()} on write and fails on read. Whether a key type can
 * be read is asked of the mapper's own read side, so every rule Jackson and the registered
 * modules have for key deserializers applies. A key serializer the user registered is left
 * alone, since reading its output back is then the user's contract.
 */
final class UnreadableKeyGuard
        extends ValueSerializerModifier
{
    private static final long serialVersionUID = 1L;

    // The mapper this guard belongs to, bound right after the mapper is constructed and
    // before it serializes anything.
    private transient volatile ObjectMapper mapper;

    // A guard belongs to exactly one mapper, whose read side it asks, so binding it again (to
    // another mapper sharing its module, say) would answer for the wrong configuration.
    synchronized void bind(ObjectMapper mapper)
    {
        if (this.mapper != null) {
            throw new IllegalStateException("This key guard is already bound to a mapper");
        }
        this.mapper = mapper;
    }

    @Override
    public ValueSerializer<?> modifyKeySerializer(SerializationConfig config, JavaType keyType,
            BeanDescription.Supplier beanDesc, ValueSerializer<?> serializer)
    {
        if (!isJacksonDefault(serializer) || isReadable(keyType)) {
            return serializer;
        }
        return new Refusing(keyType, serializer);
    }

    private static boolean isJacksonDefault(ValueSerializer<?> serializer)
    {
        return serializer.getClass().getName().startsWith("tools.jackson.databind.");
    }

    private boolean isReadable(JavaType keyType)
    {
        try {
            mapper._deserializationContext().findKeyDeserializer(keyType, null);
            return true;
        }
        catch (DatabindException noKeyDeserializer) {
            return false;
        }
    }

    private static final class Refusing
            extends StdSerializer<Object>
    {
        private final JavaType keyType;
        private final ValueSerializer<?> original;

        Refusing(JavaType keyType, ValueSerializer<?> original)
        {
            super(Object.class);
            this.keyType = keyType;
            this.original = original;
        }

        // A key deserializer named on the map property (@JsonDeserialize(keyUsing)) reads the
        // key back, but the type-level lookup cannot see it, so the property is checked here.
        @Override
        public ValueSerializer<?> createContextual(SerializationContext ctxt, BeanProperty property)
        {
            if (property != null && property.getMember() != null
                    && ctxt.getAnnotationIntrospector().findKeyDeserializer(ctxt.getConfig(), property.getMember()) != null) {
                return original.createContextual(ctxt, property);
            }
            return this;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt)
        {
            ctxt.reportBadDefinition(keyType, "A " + ClassUtil.nameOf(keyType.getRawClass())
                    + " key cannot be read back as a map key: a property name is a String, and"
                    + " no key deserializer turns that String back into this type. Give the type a"
                    + " constructor, a static valueOf or fromString, or a @JsonCreator factory taking"
                    + " one String, or provide a KeyDeserializer (registered on the mapper, or with"
                    + " @JsonDeserialize(keyUsing) on the class or the map property).");
        }
    }
}
