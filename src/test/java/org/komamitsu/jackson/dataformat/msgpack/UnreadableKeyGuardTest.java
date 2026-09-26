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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UnreadableKeyGuardTest
{
    // A guard answers for one mapper's read side, so it can be bound once only.
    @Test
    public void aGuardCanBeBoundOnlyOnce()
    {
        UnreadableKeyGuard guard = new UnreadableKeyGuard();
        assertDoesNotThrow(() -> guard.bind(new MessagePackMapper()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard.bind(new MessagePackMapper()));
        assertEquals("This key guard is already bound to a mapper", e.getMessage());
    }
}
