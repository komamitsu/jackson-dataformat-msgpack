# jackson-dataformat-msgpack

This Jackson 3.x extension library is a component to easily read and write [MessagePack](http://msgpack.org/) encoded data through jackson-databind API.

It extends standard Jackson streaming API (`JsonFactory`, `JsonParser`, `JsonGenerator`), and as such works seamlessly with all the higher level data abstractions (data binding, tree model, and pluggable extensions).

**Requirements:** Java 17+ and Jackson 3.x. For Jackson 2.x, use [`org.msgpack:jackson-dataformat-msgpack`](https://github.com/msgpack/msgpack-java/tree/main/msgpack-jackson) from msgpack-java instead.

## Why this project was resumed

This repository is where jackson-dataformat-msgpack started before it moved into msgpack-java as `msgpack-jackson`. It was picked up again for Jackson 3, as a rewrite that encodes and decodes MessagePack on Jackson's own buffers instead of through msgpack-core:

- Jackson 3 is a new major version with a new API, and a standalone module can follow its release cadence.
- msgpack-core has its own buffer layer, so every value crossed two buffer layers. Working directly on Jackson's buffers, as Jackson's own CBOR and Smile modules do, removed that overhead (writes +57%, reads +17% on the same POJO, see `jmh/results/`) and brought Jackson's read constraints, name canonicalization and buffer pooling to MessagePack.

How it works inside is described in [docs/DESIGN.md](docs/DESIGN.md).

## Install

### Maven

```xml
<dependency>
  <groupId>org.komamitsu</groupId>
  <artifactId>jackson-dataformat-msgpack</artifactId>
  <version>(version)</version>
</dependency>
```

### Sbt

```scala
libraryDependencies += "org.komamitsu" % "jackson-dataformat-msgpack" % "(version)"
```

### Gradle

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.komamitsu:jackson-dataformat-msgpack:(version)'
}
```

## Basic usage

### Serialization/Deserialization of POJO

Only thing you need to do is to instantiate `MessagePackFactory` and pass it to the constructor of `tools.jackson.databind.ObjectMapper`. And then, you can use it for MessagePack format data in the same way as jackson-databind.

```java
// Instantiate ObjectMapper for MessagePack
ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

// Serialize a Java object to byte array
ExamplePojo pojo = new ExamplePojo("komamitsu");
byte[] bytes = objectMapper.writeValueAsBytes(pojo);

// Deserialize the byte array to a Java object
ExamplePojo deserialized = objectMapper.readValue(bytes, ExamplePojo.class);
System.out.println(deserialized.getName()); // => komamitsu
```

Or more easily:

```java
ObjectMapper objectMapper = new MessagePackMapper();
```

We strongly recommend calling `MessagePackMapper.Builder#handleBigIntegerAndBigDecimalAsString()` if you serialize and/or deserialize BigInteger/BigDecimal values. See [Serialize and deserialize BigDecimal as str type internally in MessagePack format](#serialize-and-deserialize-bigdecimal-as-str-type-internally-in-messagepack-format) for details.

```java
ObjectMapper objectMapper = MessagePackMapper.builder().handleBigIntegerAndBigDecimalAsString().build();
```

### Serialization/Deserialization of List

```java
// Instantiate ObjectMapper for MessagePack
ObjectMapper objectMapper = new MessagePackMapper();

// Serialize a List to byte array
List<Object> list = new ArrayList<>();
list.add("Foo");
list.add("Bar");
list.add(42);
byte[] bytes = objectMapper.writeValueAsBytes(list);

// Deserialize the byte array to a List
List<Object> deserialized = objectMapper.readValue(bytes, new TypeReference<List<Object>>() {});
System.out.println(deserialized); // => [Foo, Bar, 42]
```

### Serialization/Deserialization of Map

```java
// Instantiate ObjectMapper for MessagePack
ObjectMapper objectMapper = new MessagePackMapper();

// Serialize a Map to byte array
Map<String, Object> map = new HashMap<>();
map.put("name", "komamitsu");
map.put("age", 42);
byte[] bytes = objectMapper.writeValueAsBytes(map);

// Deserialize the byte array to a Map
Map<String, Object> deserialized = objectMapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});
System.out.println(deserialized); // => {name=komamitsu, age=42}
```

### Example of Serialization/Deserialization over multiple languages

Java

```java
// Serialize
Map<String, Object> obj = new HashMap<String, Object>();
obj.put("foo", "hello");
obj.put("bar", "world");
byte[] bs = objectMapper.writeValueAsBytes(obj);
// bs => [-126, -93, 102, 111, 111, -91, 104, 101, 108, 108, 111,
//        -93, 98, 97, 114, -91, 119, 111, 114, 108, 100]
```

Ruby

```ruby
require 'msgpack'

# Deserialize
xs = [-126, -93, 102, 111, 111, -91, 104, 101, 108, 108, 111,
      -93, 98, 97, 114, -91, 119, 111, 114, 108, 100]
MessagePack.unpack(xs.pack("C*"))
# => {"foo"=>"hello", "bar"=>"world"}

# Serialize
["zero", 1, 2.0, nil].to_msgpack.unpack('C*')
# => [148, 164, 122, 101, 114, 111, 1, 203, 64, 0, 0, 0, 0, 0, 0, 0, 192]
```

Java

```java
// Deserialize
bs = new byte[] {(byte) 148, (byte) 164, 122, 101, 114, 111, 1,
                 (byte) 203, 64, 0, 0, 0, 0, 0, 0, 0, (byte) 192};
TypeReference<List<Object>> typeReference = new TypeReference<List<Object>>(){};
List<Object> xs = objectMapper.readValue(bs, typeReference);
// xs => [zero, 1, 2.0, null]
```

## Advanced usage

### Serialize multiple values without closing an output stream

`tools.jackson.databind.ObjectMapper` closes an output stream by default after it writes a value. If you want to serialize multiple values in a row without closing an output stream, disable `StreamWriteFeature.AUTO_CLOSE_TARGET`.

```java
OutputStream out = new FileOutputStream(tempFile);
ObjectMapper objectMapper = MessagePackMapper.builder()
        .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
        .build();

objectMapper.writeValue(out, 1);
objectMapper.writeValue(out, "two");
objectMapper.writeValue(out, 3.14);
out.close();
```

The file now holds three MessagePack values back to back. The next section shows how to read them.

### Deserialize multiple values without closing an input stream

`tools.jackson.databind.ObjectMapper` closes an input stream by default after it reads a value. If you want to deserialize multiple values in a row without closing an input stream, disable `StreamReadFeature.AUTO_CLOSE_SOURCE`.

```java
// tempFile holds the three values written in the previous section
FileInputStream in = new FileInputStream(tempFile);
ObjectMapper objectMapper = MessagePackMapper.builder()
        .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
        .build();
System.out.println(objectMapper.readValue(in, Integer.class));   // => 1
System.out.println(objectMapper.readValue(in, String.class));    // => two
System.out.println(objectMapper.readValue(in, Double.class));    // => 3.14
in.close();
```

In this mode the parser reads exactly the bytes of the value it returns, so the stream is left positioned at the next value.

### Serialize not using str8 type

Old msgpack-java (e.g 0.6.7) doesn't support MessagePack str8 type. When your application needs to communicate with such an old MessagePack library, you can disable the data type like this:

```java
ObjectMapper objectMapper = new MessagePackMapper(new MessagePackFactory().setStr8FormatSupport(false));
// A string of 32 to 255 bytes is serialized as str16 instead of str8
byte[] resultWithoutStr8Format = objectMapper.writeValueAsBytes(str8LengthString);
```

### Serialize using non-String as a key of Map

When you want to use non-String value as a key of Map, use `MessagePackKeySerializer` for key serialization.

```java
@JsonSerialize(keyUsing = MessagePackKeySerializer.class)
private Map<Integer, String> intMap = new HashMap<>();

  :

intMap.put(42, "Hello");

ObjectMapper objectMapper = new MessagePackMapper();
byte[] bytes = objectMapper.writeValueAsBytes(intMap);

Map<Integer, String> deserialized = objectMapper.readValue(bytes, new TypeReference<Map<Integer, String>>() {});
System.out.println(deserialized);   // => {42=Hello}
```

To apply it to every map key without annotating each field, register it through a module:

```java
SimpleModule module = new SimpleModule().addKeySerializer(Object.class, new MessagePackKeySerializer());
ObjectMapper objectMapper = MessagePackMapper.builder().addModule(module).build();
```

### Serialize and deserialize BigDecimal as str type internally in MessagePack format

`jackson-dataformat-msgpack` represents BigDecimal values as float type in MessagePack format by default for backward compatibility. But the default behavior could fail when handling too large value for `double` type. So we strongly recommend calling `MessagePackMapper.Builder#handleBigIntegerAndBigDecimalAsString()` to internally handle BigDecimal values as String.

```java
ObjectMapper objectMapper = MessagePackMapper.builder().handleBigIntegerAndBigDecimalAsString().build();

Pojo obj = new Pojo();
// This value is too large to be serialized as double
obj.value = new BigDecimal("1234567890.98765432100");

byte[] converted = objectMapper.writeValueAsBytes(obj);

System.out.println(objectMapper.readValue(converted, Pojo.class));   // => Pojo{value=1234567890.98765432100}
```

`MessagePackMapper.Builder#handleBigIntegerAndBigDecimalAsString()` is equivalent to the following configuration.

```java
ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());
objectMapper.configOverride(BigInteger.class).setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
objectMapper.configOverride(BigDecimal.class).setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
```

### Serialize and deserialize Instant instances as MessagePack extension type

`timestamp` extension type is defined in MessagePack as type:-1. Registering `TimestampExtensionModule.INSTANCE` module enables automatic serialization and deserialization of `java.time.Instant` to/from the MessagePack extension type.

```java
ObjectMapper objectMapper = MessagePackMapper.builder()
        .addModule(TimestampExtensionModule.INSTANCE)
        .build();
Pojo pojo = new Pojo();
// The type of `timestamp` variable is Instant
pojo.timestamp = Instant.now();
byte[] bytes = objectMapper.writeValueAsBytes(pojo);

// The Instant instance is serialized as MessagePack extension type (type: -1)

Pojo deserialized = objectMapper.readValue(bytes, Pojo.class);
System.out.println(deserialized);   // "2022-09-14T08:47:24.922Z"
```

### Deserialize extension types with ExtensionTypeCustomDeserializers

`ExtensionTypeCustomDeserializers` helps you to deserialize your own custom extension types easily.

#### Deserialize extension type value directly

```java
// In this application, extension type 59 is used for byte[]
byte[] bytes = new MessagePackMapper().writeValueAsBytes(new MessagePackExtensionType((byte) 59, hexspeak));

// Register the type and a deserializer to ExtensionTypeCustomDeserializers
ExtensionTypeCustomDeserializers extTypeCustomDesers = new ExtensionTypeCustomDeserializers();
extTypeCustomDesers.addCustomDeser((byte) 59, data -> {
    if (Arrays.equals(data,
              new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE})) {
        return "Java";
    }
    return "Not Java";
});

ObjectMapper objectMapper = new ObjectMapper(
        new MessagePackFactory().setExtTypeCustomDesers(extTypeCustomDesers));

System.out.println(objectMapper.readValue(bytes, Object.class));
  // => Java
```

#### Use extension type as Map key

```java
static class TripleBytesPojo
{
  public byte first;
  public byte second;
  public byte third;

  public TripleBytesPojo(byte first, byte second, byte third)
  {
    this.first = first;
    this.second = second;
    this.third = third;
  }

  @Override
  public boolean equals(Object o)
  {
    :
  }

  @Override
  public int hashCode()
  {
    :
  }

  @Override
  public String toString()
  {
    // This key format is used when serialized as map key
    return String.format("%d-%d-%d", first, second, third);
  }

  static class KeyDeserializer
      extends tools.jackson.databind.KeyDeserializer
  {
    @Override
    public Object deserializeKey(String key, DeserializationContext ctxt)
    {
      String[] values = key.split("-");
      return new TripleBytesPojo(Byte.parseByte(values[0]), Byte.parseByte(values[1]), Byte.parseByte(values[2]));
    }
  }

  static TripleBytesPojo deserialize(byte[] bytes)
  {
    return new TripleBytesPojo(bytes[0], bytes[1], bytes[2]);
  }
}

:

byte extTypeCode = 42;

ExtensionTypeCustomDeserializers extTypeCustomDesers = new ExtensionTypeCustomDeserializers();
extTypeCustomDesers.addCustomDeser(extTypeCode, new ExtensionTypeCustomDeserializers.Deser()
{
  @Override
  public Object deserialize(byte[] value)
        throws IOException
  {
    return TripleBytesPojo.deserialize(value);
  }
});

SimpleModule module = new SimpleModule();
module.addKeyDeserializer(TripleBytesPojo.class, new TripleBytesPojo.KeyDeserializer());
ObjectMapper objectMapper = MessagePackMapper.builder(
        new MessagePackFactory().setExtTypeCustomDesers(extTypeCustomDesers))
        .addModule(module)
        .build();

Map<TripleBytesPojo, Integer> deserializedMap =
        objectMapper.readValue(serializedData,
            new TypeReference<Map<TripleBytesPojo, Integer>>() {});
```

#### Use extension type as Map value

```java
static class TripleBytesPojo
{
  public byte first;
  public byte second;
  public byte third;

  public TripleBytesPojo(byte first, byte second, byte third)
  {
    this.first = first;
    this.second = second;
    this.third = third;
  }

  static class Deserializer
      extends StdDeserializer<TripleBytesPojo>
  {
    protected Deserializer()
    {
      super(TripleBytesPojo.class);
    }

    @Override
    public TripleBytesPojo deserialize(JsonParser p, DeserializationContext ctxt)
    {
      return TripleBytesPojo.deserialize(p.getBinaryValue());
    }
  }

  static TripleBytesPojo deserialize(byte[] bytes)
  {
    return new TripleBytesPojo(bytes[0], bytes[1], bytes[2]);
  }
}

:

byte extTypeCode = 42;

ExtensionTypeCustomDeserializers extTypeCustomDesers = new ExtensionTypeCustomDeserializers();
extTypeCustomDesers.addCustomDeser(extTypeCode, new ExtensionTypeCustomDeserializers.Deser()
{
  @Override
  public Object deserialize(byte[] value)
      throws IOException
  {
    return TripleBytesPojo.deserialize(value);
  }
});

SimpleModule module = new SimpleModule();
module.addDeserializer(TripleBytesPojo.class, new TripleBytesPojo.Deserializer());
ObjectMapper objectMapper = MessagePackMapper.builder(
        new MessagePackFactory().setExtTypeCustomDesers(extTypeCustomDesers))
        .addModule(module)
        .build();

Map<String, TripleBytesPojo> deserializedMap =
        objectMapper.readValue(serializedData,
            new TypeReference<Map<String, TripleBytesPojo>>() {});
```
