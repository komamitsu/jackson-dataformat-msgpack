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

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.introspect.AnnotatedAndMetadata;
import tools.jackson.databind.introspect.AnnotatedConstructor;
import tools.jackson.databind.introspect.AnnotatedMethod;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.util.ClassUtil;

import java.io.Serializable;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Refuses to write a map key that could not be read back. A property name is a String, so a
 * key survives only if Jackson can turn that String back into its type; for anything else
 * Jackson falls back to {@code toString()} on write and fails on read. The check mirrors how
 * Jackson picks a key deserializer: its built-in JDK and java.time types, enums, a
 * {@code @JsonDeserialize(keyUsing)} on the class, and a String-based creator. A key serializer
 * the user registered is left alone, since reading its output back is then the user's contract.
 */
final class UnreadableKeyGuard
        extends ValueSerializerModifier
{
    private static final long serialVersionUID = 1L;

    // Types Jackson has a built-in key deserializer for (JDKKeyDeserializer and the java.time
    // key deserializers).
    private static final List<Class<?>> READABLE = Arrays.asList(
            String.class, Object.class, CharSequence.class, Serializable.class,
            Boolean.class, Byte.class, Short.class, Character.class, Integer.class, Long.class,
            Float.class, Double.class, UUID.class, Date.class, Calendar.class, URI.class, URL.class,
            Class.class, Locale.class, Currency.class, byte[].class,
            Duration.class, Instant.class, LocalDateTime.class, LocalDate.class, LocalTime.class,
            MonthDay.class, OffsetDateTime.class, OffsetTime.class, Period.class, Year.class,
            YearMonth.class, ZonedDateTime.class, ZoneId.class);
    // Of those, the ones whose values are usually a subtype (GregorianCalendar, java.sql.Date,
    // a ZoneId region), which read back as the declared type.
    private static final List<Class<?>> READABLE_WITH_SUBTYPES = Arrays.asList(
            Date.class, Calendar.class, ZoneId.class);

    @Override
    public ValueSerializer<?> modifyKeySerializer(SerializationConfig config, JavaType keyType,
            BeanDescription.Supplier beanDesc, ValueSerializer<?> serializer)
    {
        if (!isJacksonDefault(serializer) || isReadable(config, keyType, beanDesc)) {
            return serializer;
        }
        return new Refusing(keyType);
    }

    private static boolean isJacksonDefault(ValueSerializer<?> serializer)
    {
        return serializer.getClass().getName().startsWith("tools.jackson.databind.");
    }

    private static boolean isReadable(SerializationConfig config, JavaType keyType, BeanDescription.Supplier beanDesc)
    {
        Class<?> raw = keyType.getRawClass();
        if (raw.isPrimitive()) {
            raw = ClassUtil.wrapperType(raw);
        }
        if (ClassUtil.isEnumType(raw) || READABLE.contains(raw)) {
            return true;
        }
        for (Class<?> readable : READABLE_WITH_SUBTYPES) {
            if (readable.isAssignableFrom(raw)) {
                return true;
            }
        }
        if (config.getAnnotationIntrospector().findKeyDeserializer(config, beanDesc.getClassInfo()) != null) {
            return true;
        }
        return hasStringCreator(beanDesc.get());
    }

    // The same creators Jackson's string-based key deserializer accepts: a constructor taking
    // one String, or a factory method (valueOf, fromString or @JsonCreator) taking one String.
    private static boolean hasStringCreator(BeanDescription desc)
    {
        for (AnnotatedAndMetadata<AnnotatedConstructor, JsonCreator.Mode> ctor : desc.getConstructorsWithMode()) {
            if (ctor.annotated.getParameterCount() == 1 && ctor.annotated.getRawParameterType(0) == String.class) {
                return true;
            }
        }
        for (AnnotatedAndMetadata<AnnotatedMethod, JsonCreator.Mode> factory : desc.getFactoryMethodsWithMode()) {
            if (factory.annotated.getParameterCount() == 1 && factory.annotated.getRawParameterType(0) == String.class
                    && factory.metadata != JsonCreator.Mode.PROPERTIES) {
                return true;
            }
        }
        return false;
    }

    private static final class Refusing
            extends StdSerializer<Object>
    {
        private final JavaType keyType;

        Refusing(JavaType keyType)
        {
            super(Object.class);
            this.keyType = keyType;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt)
        {
            ctxt.reportBadDefinition(keyType, "A " + ClassUtil.nameOf(keyType.getRawClass())
                    + " key cannot be read back as a map key: a property name is a String, and"
                    + " nothing turns that String back into this type. Give the type a constructor"
                    + " or a static valueOf(String) taking one String, or register a key serializer"
                    + " and a KeyDeserializer for it.");
        }
    }
}
