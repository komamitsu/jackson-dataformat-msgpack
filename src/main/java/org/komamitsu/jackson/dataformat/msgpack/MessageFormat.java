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

/**
 * MessagePack wire formats, keyed by the first byte of a value.
 */
enum MessageFormat
{
    POSFIXINT(ValueType.INTEGER),
    FIXMAP(ValueType.MAP),
    FIXARRAY(ValueType.ARRAY),
    FIXSTR(ValueType.STRING),
    NIL(ValueType.NIL),
    NEVER_USED(null),
    BOOLEAN(ValueType.BOOLEAN),
    BIN8(ValueType.BINARY),
    BIN16(ValueType.BINARY),
    BIN32(ValueType.BINARY),
    EXT8(ValueType.EXTENSION),
    EXT16(ValueType.EXTENSION),
    EXT32(ValueType.EXTENSION),
    FLOAT32(ValueType.FLOAT),
    FLOAT64(ValueType.FLOAT),
    UINT8(ValueType.INTEGER),
    UINT16(ValueType.INTEGER),
    UINT32(ValueType.INTEGER),
    UINT64(ValueType.INTEGER),
    INT8(ValueType.INTEGER),
    INT16(ValueType.INTEGER),
    INT32(ValueType.INTEGER),
    INT64(ValueType.INTEGER),
    FIXEXT1(ValueType.EXTENSION),
    FIXEXT2(ValueType.EXTENSION),
    FIXEXT4(ValueType.EXTENSION),
    FIXEXT8(ValueType.EXTENSION),
    FIXEXT16(ValueType.EXTENSION),
    STR8(ValueType.STRING),
    STR16(ValueType.STRING),
    STR32(ValueType.STRING),
    ARRAY16(ValueType.ARRAY),
    ARRAY32(ValueType.ARRAY),
    MAP16(ValueType.MAP),
    MAP32(ValueType.MAP),
    NEGFIXINT(ValueType.INTEGER);

    enum ValueType
    {
        NIL, BOOLEAN, INTEGER, FLOAT, STRING, BINARY, ARRAY, MAP, EXTENSION
    }

    private static final MessageFormat[] TABLE = new MessageFormat[256];

    static {
        for (int b = 0; b < 256; b++) {
            TABLE[b] = toMessageFormat((byte) b);
        }
    }

    private final ValueType valueType;

    MessageFormat(ValueType valueType)
    {
        this.valueType = valueType;
    }

    ValueType getValueType()
    {
        return valueType;
    }

    static MessageFormat valueOf(byte b)
    {
        return TABLE[b & 0xff];
    }

    private static MessageFormat toMessageFormat(byte b)
    {
        if ((b & 0x80) == 0) {
            return POSFIXINT;
        }
        if ((b & 0xe0) == 0xe0) {
            return NEGFIXINT;
        }
        if ((b & 0xe0) == 0xa0) {
            return FIXSTR;
        }
        if ((b & 0xf0) == 0x90) {
            return FIXARRAY;
        }
        if ((b & 0xf0) == 0x80) {
            return FIXMAP;
        }
        switch (b) {
            case Code.NIL:
                return NIL;
            case Code.FALSE:
            case Code.TRUE:
                return BOOLEAN;
            case Code.BIN8:
                return BIN8;
            case Code.BIN16:
                return BIN16;
            case Code.BIN32:
                return BIN32;
            case Code.EXT8:
                return EXT8;
            case Code.EXT16:
                return EXT16;
            case Code.EXT32:
                return EXT32;
            case Code.FLOAT32:
                return FLOAT32;
            case Code.FLOAT64:
                return FLOAT64;
            case Code.UINT8:
                return UINT8;
            case Code.UINT16:
                return UINT16;
            case Code.UINT32:
                return UINT32;
            case Code.UINT64:
                return UINT64;
            case Code.INT8:
                return INT8;
            case Code.INT16:
                return INT16;
            case Code.INT32:
                return INT32;
            case Code.INT64:
                return INT64;
            case Code.FIXEXT1:
                return FIXEXT1;
            case Code.FIXEXT2:
                return FIXEXT2;
            case Code.FIXEXT4:
                return FIXEXT4;
            case Code.FIXEXT8:
                return FIXEXT8;
            case Code.FIXEXT16:
                return FIXEXT16;
            case Code.STR8:
                return STR8;
            case Code.STR16:
                return STR16;
            case Code.STR32:
                return STR32;
            case Code.ARRAY16:
                return ARRAY16;
            case Code.ARRAY32:
                return ARRAY32;
            case Code.MAP16:
                return MAP16;
            case Code.MAP32:
                return MAP32;
            default:
                return NEVER_USED;
        }
    }

    /**
     * Format byte values from the MessagePack specification.
     */
    static final class Code
    {
        static final byte POSFIXINT_MASK = (byte) 0x80;
        static final byte FIXMAP_PREFIX = (byte) 0x80;
        static final byte FIXARRAY_PREFIX = (byte) 0x90;
        static final byte FIXSTR_PREFIX = (byte) 0xa0;
        static final byte NIL = (byte) 0xc0;
        static final byte NEVER_USED = (byte) 0xc1;
        static final byte FALSE = (byte) 0xc2;
        static final byte TRUE = (byte) 0xc3;
        static final byte BIN8 = (byte) 0xc4;
        static final byte BIN16 = (byte) 0xc5;
        static final byte BIN32 = (byte) 0xc6;
        static final byte EXT8 = (byte) 0xc7;
        static final byte EXT16 = (byte) 0xc8;
        static final byte EXT32 = (byte) 0xc9;
        static final byte FLOAT32 = (byte) 0xca;
        static final byte FLOAT64 = (byte) 0xcb;
        static final byte UINT8 = (byte) 0xcc;
        static final byte UINT16 = (byte) 0xcd;
        static final byte UINT32 = (byte) 0xce;
        static final byte UINT64 = (byte) 0xcf;
        static final byte INT8 = (byte) 0xd0;
        static final byte INT16 = (byte) 0xd1;
        static final byte INT32 = (byte) 0xd2;
        static final byte INT64 = (byte) 0xd3;
        static final byte FIXEXT1 = (byte) 0xd4;
        static final byte FIXEXT2 = (byte) 0xd5;
        static final byte FIXEXT4 = (byte) 0xd6;
        static final byte FIXEXT8 = (byte) 0xd7;
        static final byte FIXEXT16 = (byte) 0xd8;
        static final byte STR8 = (byte) 0xd9;
        static final byte STR16 = (byte) 0xda;
        static final byte STR32 = (byte) 0xdb;
        static final byte ARRAY16 = (byte) 0xdc;
        static final byte ARRAY32 = (byte) 0xdd;
        static final byte MAP16 = (byte) 0xde;
        static final byte MAP32 = (byte) 0xdf;
        static final byte NEGFIXINT_PREFIX = (byte) 0xe0;
        static final byte EXT_TIMESTAMP = (byte) -1;

        private Code()
        {
        }
    }
}
