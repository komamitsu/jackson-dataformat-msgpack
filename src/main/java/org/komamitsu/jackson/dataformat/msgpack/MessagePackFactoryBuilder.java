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

import tools.jackson.core.ErrorReportConfiguration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.base.DecorableTSFactory;

public class MessagePackFactoryBuilder
        extends DecorableTSFactory.DecorableTSFBuilder<MessagePackFactory, MessagePackFactoryBuilder>
{
    private boolean str8FormatSupport;
    private boolean supportIntegerKeys;
    private boolean containerMapKeySupport;
    private ExtensionTypeCustomDeserializers extTypeCustomDesers;

    public MessagePackFactoryBuilder()
    {
        super(StreamReadConstraints.defaults(), StreamWriteConstraints.defaults(),
                ErrorReportConfiguration.defaults(), 0, 0);
        this.str8FormatSupport = true;
        this.supportIntegerKeys = false;
        this.containerMapKeySupport = false;
    }

    public MessagePackFactoryBuilder(MessagePackFactory base)
    {
        super(base);
        this.str8FormatSupport = base.isStr8FormatSupport();
        this.supportIntegerKeys = base.isSupportIntegerKeys();
        this.containerMapKeySupport = base.isContainerMapKeySupport();
        ExtensionTypeCustomDeserializers srcDesers = base.getExtTypeCustomDesers();
        this.extTypeCustomDesers = srcDesers == null ? null : new ExtensionTypeCustomDeserializers(srcDesers);
    }

    /**
     * Whether strings of 32 to 255 bytes use the str8 format. Disable for readers that
     * predate str8 in the MessagePack specification, which then get str16 instead.
     */
    public MessagePackFactoryBuilder str8FormatSupport(boolean v)
    {
        this.str8FormatSupport = v;
        return this;
    }

    public MessagePackFactoryBuilder containerMapKeySupport(boolean v)
    {
        this.containerMapKeySupport = v;
        return this;
    }

    public MessagePackFactoryBuilder supportIntegerKeys(boolean v)
    {
        this.supportIntegerKeys = v;
        return this;
    }

    public MessagePackFactoryBuilder extTypeCustomDesers(ExtensionTypeCustomDeserializers desers)
    {
        this.extTypeCustomDesers = desers;
        return this;
    }

    public boolean str8FormatSupport()
    {
        return str8FormatSupport;
    }

    public boolean supportIntegerKeys()
    {
        return supportIntegerKeys;
    }

    public boolean containerMapKeySupport()
    {
        return containerMapKeySupport;
    }

    public ExtensionTypeCustomDeserializers extTypeCustomDesers()
    {
        return extTypeCustomDesers;
    }

    @Override
    public MessagePackFactory build()
    {
        return new MessagePackFactory(this);
    }
}
