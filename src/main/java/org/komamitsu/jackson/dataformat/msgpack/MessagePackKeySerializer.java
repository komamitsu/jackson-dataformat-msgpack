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
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.ser.jdk.JDKKeySerializers;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.util.ClassUtil;

/**
 * Writes a map key as a native MessagePack value when MessagePack has a scalar for its type:
 * numbers, booleans, nil, strings, binary and extension values. Any other key is written the way
 * Jackson writes it as a JSON property name, so it reads back through the same
 * {@code KeyDeserializer}, and a key never becomes a map or an array, which no property name
 * can represent.
 */
public class MessagePackKeySerializer
        extends StdSerializer<Object>
{
    public MessagePackKeySerializer()
    {
        super(Object.class);
    }

    @Override
    public void serialize(Object value, JsonGenerator jgen, SerializationContext provider)
    {
        if (MessagePackGenerator.isScalarKey(value)) {
            jgen.writeName(new MessagePackSerializedString(value));
            return;
        }
        Class<?> type = value.getClass();
        ValueSerializer<Object> registered = provider.findKeySerializer(type, null);
        if (!(registered instanceof MessagePackKeySerializer)) {
            registered.serialize(value, jgen, provider);
            return;
        }
        // The lookup found this serializer again (registered for Object, say), so repeat the
        // rest of Jackson's key serializer selection here: known JDK types, then @JsonKey or
        // @JsonValue, then enums and toString().
        SerializationConfig config = provider.getConfig();
        ValueSerializer<Object> std = JDKKeySerializers.getStdKeySerializer(config, type, false);
        if (std != null) {
            std.serialize(value, jgen, provider);
            return;
        }
        BeanDescription desc = provider.lazyIntrospectBeanDescription(provider.constructType(type)).get();
        AnnotatedMember accessor = desc.findJsonKeyAccessor();
        if (accessor == null) {
            accessor = desc.findJsonValueAccessor();
        }
        if (accessor != null) {
            if (config.canOverrideAccessModifiers()) {
                ClassUtil.checkAndFixAccess(accessor.getMember(),
                        config.isEnabled(MapperFeature.OVERRIDE_PUBLIC_ACCESS_MODIFIERS));
            }
            // The accessor's value is a key in its own right, and may well be a scalar.
            serialize(accessor.getValue(value), jgen, provider);
            return;
        }
        JDKKeySerializers.getFallbackKeySerializer(config, type, desc.getClassInfo())
                .serialize(value, jgen, provider);
    }
}
