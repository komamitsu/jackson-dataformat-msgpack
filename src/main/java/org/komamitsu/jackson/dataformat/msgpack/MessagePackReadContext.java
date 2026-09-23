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
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.io.ContentReference;
import tools.jackson.core.json.DupDetector;

/**
 * Replacement of {@link tools.jackson.core.json.JsonReadContext}
 * to support features needed by MessagePack format.
 */
public final class MessagePackReadContext
    extends TokenStreamContext
{
    private final MessagePackReadContext parent;

    private final DupDetector dups;

    /**
     * For fixed-size Arrays, Objects, this indicates expected number of entries.
     */
    private int expEntryCount;

    private String currentName;

    private Object currentValue;

    private MessagePackReadContext child;

    // Whether a nil key was already seen in this object, for duplicate detection.
    private boolean sawNullName;

    // For TYPE_OBJECT: true once this entry's name has been read, so the next token is its
    // value. Kept here rather than derived from the parser's current token, which callers may
    // clear with clearCurrentToken() without consuming any input.
    private boolean gotName;

    private MessagePackReadContext(MessagePackReadContext parent, DupDetector dups,
                                  int type, int expEntryCount)
    {
        super();
        this.parent = parent;
        this.dups = dups;
        _type = type;
        this.expEntryCount = expEntryCount;
        _index = -1;
        _nestingDepth = parent == null ? 0 : parent._nestingDepth + 1;
    }

    private void reset(int type, int expEntryCount)
    {
        _type = type;
        this.expEntryCount = expEntryCount;
        _index = -1;
        currentName = null;
        currentValue = null;
        sawNullName = false;
        gotName = false;
        if (dups != null) {
            dups.reset();
        }
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

    static MessagePackReadContext createRootContext(DupDetector dups)
    {
        return new MessagePackReadContext(null, dups, TYPE_ROOT, -1);
    }

    MessagePackReadContext createChildArrayContext(int expEntryCount)
    {
        MessagePackReadContext ctxt = child;
        if (ctxt == null) {
            ctxt = new MessagePackReadContext(this,
                    (dups == null) ? null : dups.child(),
                            TYPE_ARRAY, expEntryCount);
            child = ctxt;
        }
        else {
            ctxt.reset(TYPE_ARRAY, expEntryCount);
        }
        return ctxt;
    }

    MessagePackReadContext createChildObjectContext(int expEntryCount)
    {
        MessagePackReadContext ctxt = child;
        if (ctxt == null) {
            ctxt = new MessagePackReadContext(this,
                    (dups == null) ? null : dups.child(),
                    TYPE_OBJECT, expEntryCount);
            child = ctxt;
            return ctxt;
        }
        ctxt.reset(TYPE_OBJECT, expEntryCount);
        return ctxt;
    }

    @Override
    public String currentName()
    {
        return currentName;
    }

    @Override
    public MessagePackReadContext getParent()
    {
        return parent;
    }

    /**
     * Moves on to the next entry of this container. The count comes from the container's
     * header, so the end is reached by counting rather than by reading a terminator.
     */
    void advance()
    {
        ++_index;
    }

    boolean atEnd()
    {
        return _index == expEntryCount;
    }

    public TokenStreamLocation startLocation(ContentReference srcRef)
    {
        return new TokenStreamLocation(srcRef, 1L, -1, -1);
    }

    /**
     * Whether the next token of this object is an entry's name rather than its value.
     */
    boolean atNamePosition()
    {
        return _type == TYPE_OBJECT && !gotName;
    }

    /**
     * Records that the name's value is being read, so the entry after it starts a new name.
     */
    void valueRead()
    {
        gotName = false;
    }

    void setCurrentName(String name)
    {
        gotName = true;
        currentName = name;
        if (dups != null) {
            checkDup(dups, name);
        }
    }

    private void checkDup(DupDetector dd, String name)
    {
        // A nil key has no String for DupDetector, so it is tracked here.
        boolean dup;
        if (name == null) {
            dup = sawNullName;
            sawNullName = true;
        }
        else {
            dup = dd.isDup(name);
        }
        if (dup) {
            throw new StreamReadException(null,
                    "Duplicate field '" + name + "'", dd.findLocation());
        }
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder(64);
        switch (_type) {
        case TYPE_ROOT:
            sb.append("/");
            break;
        case TYPE_ARRAY:
            sb.append('[');
            sb.append(getCurrentIndex());
            sb.append(']');
            break;
        case TYPE_OBJECT:
            sb.append('{');
            if (currentName != null) {
                sb.append('"');
                sb.append(currentName.replace("\\", "\\\\").replace("\"", "\\\""));
                sb.append('"');
            }
            else {
                sb.append('?');
            }
            sb.append('}');
            break;
        }
        return sb.toString();
    }
}
