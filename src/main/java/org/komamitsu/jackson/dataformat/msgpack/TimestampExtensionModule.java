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
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.time.Instant;

public class TimestampExtensionModule
{
    public static final byte EXT_TYPE = -1;
    public static final SimpleModule INSTANCE = new SimpleModule("msgpack-ext-timestamp");

    static {
        INSTANCE.addSerializer(Instant.class, new InstantSerializer(Instant.class));
        INSTANCE.addDeserializer(Instant.class, new InstantDeserializer(Instant.class));
    }

    private static class InstantSerializer extends StdSerializer<Instant>
    {
        protected InstantSerializer(Class<Instant> t)
        {
            super(t);
        }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext provider)
        {
            gen.writePOJO(new MessagePackExtensionType(EXT_TYPE, MessagePackWriter.timestampPayload(value)));
        }
    }

    private static class InstantDeserializer extends StdDeserializer<Instant>
    {
        protected InstantDeserializer(Class<?> vc)
        {
            super(vc);
        }

        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt)
        {
            try {
                MessagePackExtensionType ext = p.readValueAs(MessagePackExtensionType.class);
                if (ext.getType() != EXT_TYPE) {
                    ctxt.reportInputMismatch(Instant.class,
                            "Unexpected extension type (0x%X) for Instant object", ext.getType() & 0xFF);
                    return null; // unreachable
                }
                byte[] data = ext.getData();
                return new MessagePackReader(data, 0, data.length)
                        .unpackTimestamp(new ExtensionTypeHeader(EXT_TYPE, data.length));
            }
            catch (IOException e) {
                throw _wrapIOFailure(ctxt, e);
            }
        }
    }

    private TimestampExtensionModule()
    {
    }
}
