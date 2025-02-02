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
package io.trino.plugin.pulsar.decoder;

import java.util.List;
import java.util.Map;

public class DecoderTestMessage
{
    public static enum TestEnum
    {
        TEST_ENUM_1,
        TEST_ENUM_2,
        TEST_ENUM_3
    }

    public int intField;
    public String stringField;
    public float floatField;
    public double doubleField;
    public boolean booleanField;
    public long longField;
    @org.apache.avro.reflect.AvroSchema("{ \"type\": \"long\", \"logicalType\": \"timestamp-millis\" }")
    public long timestampField;
    @org.apache.avro.reflect.AvroSchema("{ \"type\": \"int\", \"logicalType\": \"time-millis\" }")
    public int timeField;
    @org.apache.avro.reflect.AvroSchema("{ \"type\": \"int\", \"logicalType\": \"date\" }")
    public int dateField;
    public TestRow rowField;
    public TestEnum enumField;

    public List<String> arrayField;
    public Map<String, Long> mapField;
    public CompositeRow compositeRow;

    public static class TestRow
    {
        public String stringField;
        public int intField;
        public NestedRow nestedRow;
    }

    public static class NestedRow
    {
        public String stringField;
        public long longField;
    }

    public static class CompositeRow
    {
        public String stringField;
        public List<NestedRow> arrayField;
        public Map<String, NestedRow> mapField;
        public NestedRow nestedRow;
        public Map<String, List<Long>> structedField;
    }

    /**
     * POJO for cyclic detect.
     */
    public static class CyclicFoo
    {
        public String getField1()
        {
            return field1;
        }

        public void setField1(String field1)
        {
            this.field1 = field1;
        }

        public Integer getField2()
        {
            return field2;
        }

        public void setField2(Integer field2)
        {
            this.field2 = field2;
        }

        public CyclicBoo getBoo()
        {
            return boo;
        }

        public void setBoo(CyclicBoo boo)
        {
            this.boo = boo;
        }

        private String field1;
        private Integer field2;
        private CyclicBoo boo;
    }

    public static class CyclicBoo
    {
        public String getField1()
        {
            return field1;
        }

        public void setField1(String field1)
        {
            this.field1 = field1;
        }

        public Boolean getField2()
        {
            return field2;
        }

        public void setField2(Boolean field2)
        {
            this.field2 = field2;
        }

        public CyclicFoo getFoo()
        {
            return foo;
        }

        public void setFoo(CyclicFoo foo)
        {
            this.foo = foo;
        }

        private String field1;
        private Boolean field2;
        private CyclicFoo foo;
    }
}
