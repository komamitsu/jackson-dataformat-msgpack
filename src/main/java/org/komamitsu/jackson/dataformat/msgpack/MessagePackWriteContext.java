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

import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.exc.StreamWriteException;
import tools.jackson.core.json.DupDetector;

class MessagePackWriteContext extends TokenStreamContext
{
    private final MessagePackWriteContext parent;
    private MessagePackWriteContext childToRecycle;
    private DupDetector dups;
    private String currentName;
    private Object currentValue;
    // For TYPE_OBJECT: true after writeName (expecting value), false after writeValue (expecting name)
    private boolean gotName;
    // Whether a nil key was already written in this object, for duplicate detection.
    private boolean sawNullName;
    // Where this container's header sits in the writer buffer, and how many bytes were
    // reserved for it, so the header can be patched with the final count on close.
    private int headerOffset;
    private int reservedHeaderLength;

    private MessagePackWriteContext(int type, MessagePackWriteContext parent, DupDetector dups, int nestingDepth)
    {
        super(type, -1);
        this.parent = parent;
        this.dups = dups;
        _nestingDepth = nestingDepth;
    }

    private MessagePackWriteContext reset(int type, Object value)
    {
        _type = type;
        _index = -1;
        gotName = false;
        sawNullName = false;
        currentName = null;
        currentValue = value;
        if (dups != null) {
            dups.reset();
        }
        return this;
    }

    static MessagePackWriteContext createRootContext(DupDetector dups)
    {
        return createRootContext(dups, 0);
    }

    /**
     * A root context that counts nesting from the given depth, for a generator that writes
     * a value inside another generator's containers.
     */
    static MessagePackWriteContext createRootContext(DupDetector dups, int nestingDepth)
    {
        return new MessagePackWriteContext(TYPE_ROOT, null, dups, nestingDepth);
    }

    MessagePackWriteContext createChildArrayContext(Object value)
    {
        return child().reset(TYPE_ARRAY, value);
    }

    MessagePackWriteContext createChildObjectContext(Object value)
    {
        return child().reset(TYPE_OBJECT, value);
    }

    private MessagePackWriteContext child()
    {
        MessagePackWriteContext ctx = childToRecycle;
        if (ctx == null) {
            ctx = new MessagePackWriteContext(TYPE_ARRAY, this, dups == null ? null : dups.child(), _nestingDepth + 1);
            childToRecycle = ctx;
        }
        return ctx;
    }

    @Override
    public MessagePackWriteContext getParent()
    {
        return parent;
    }

    void setHeader(int offset, int reservedLength)
    {
        headerOffset = offset;
        reservedHeaderLength = reservedLength;
    }

    int headerOffset()
    {
        return headerOffset;
    }

    int reservedHeaderLength()
    {
        return reservedHeaderLength;
    }

    boolean isExpectingValue()
    {
        return _type == TYPE_OBJECT && gotName;
    }

    boolean acceptsValue()
    {
        return _type != TYPE_OBJECT || gotName;
    }

    /**
     * Counts one entry of this container. The count is what closeContainer patches into the
     * reserved header, so every value must pass through here exactly once.
     */
    void countValue()
    {
        gotName = false;
        ++_index;
    }

    boolean acceptsName()
    {
        return _type == TYPE_OBJECT && !gotName;
    }

    void setName(String name) throws StreamWriteException
    {
        // Checked first: a rejected name must leave the context as it was, or closing the
        // generator would write a value for a name that never reached the output.
        if (dups != null) {
            checkDup(name);
        }
        currentName = name;
        gotName = true;
    }

    /**
     * The two halves of {@link #setName}, for a key whose bytes are written before its name is
     * recorded. {@code DupDetector} remembers every name it is shown and cannot forget one, so
     * the check runs only after the key's bytes are encoded: a name checked before an encoding
     * that then failed would be rejected on the next attempt.
     */
    void checkDuplicate(String name) throws StreamWriteException
    {
        if (dups != null) {
            checkDup(name);
        }
    }

    void setCheckedName(String name)
    {
        currentName = name;
        gotName = true;
    }

    private void checkDup(String name) throws StreamWriteException
    {
        // A nil key has no String for DupDetector, so it is tracked here.
        boolean dup;
        if (name == null) {
            dup = sawNullName;
            sawNullName = true;
        }
        else {
            dup = dups.isDup(name);
        }
        if (dup) {
            throw new StreamWriteException(null, "Duplicate Object property \"" + name + "\"");
        }
    }

    @Override
    public String currentName()
    {
        return currentName;
    }

    @Override
    public Object currentValue()
    {
        return currentValue;
    }

    @Override
    public void assignCurrentValue(Object v)
    {
        currentValue = v;
    }
}
