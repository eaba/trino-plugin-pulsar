/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.pulsar.decoder.primitive;

import io.trino.plugin.pulsar.decoder.DecoderTestUtil;
import io.trino.spi.block.Block;
import io.trino.spi.type.Type;

/**
 * TestUtil for PrimitiveDecoder.
 * CheckXXXValues() is mock method. Because Primitive is single hierarchy, so CheckXXXValues are never actually
 * invoked.
 */
public class PrimitiveDecoderTestUtil
        extends DecoderTestUtil
{
    public PrimitiveDecoderTestUtil()
    {
        super();
    }

    @Override
    public void checkArrayValues(Block block, Type type, Object value) {}

    @Override
    public void checkMapValues(Block block, Type type, Object value) {}

    @Override
    public void checkRowValues(Block block, Type type, Object value) {}

    @Override
    public void checkPrimitiveValue(Object actual, Object expected) {}
}
