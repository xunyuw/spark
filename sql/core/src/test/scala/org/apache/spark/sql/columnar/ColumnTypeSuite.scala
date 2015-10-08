/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.columnar

import java.nio.ByteBuffer

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.CatalystTypeConverters
import org.apache.spark.sql.catalyst.expressions.GenericMutableRow
import org.apache.spark.sql.columnar.ColumnarTestUtils._
import org.apache.spark.sql.types._
import org.apache.spark.{Logging, SparkFunSuite}


class ColumnTypeSuite extends SparkFunSuite with Logging {
  private val DEFAULT_BUFFER_SIZE = 512
  private val MAP_TYPE = MAP(MapType(IntegerType, StringType))
  private val ARRAY_TYPE = ARRAY(ArrayType(IntegerType))
  private val STRUCT_TYPE = STRUCT(StructType(StructField("a", StringType) :: Nil))

  test("defaultSize") {
    val checks = Map(
      NULL-> 0, BOOLEAN -> 1, BYTE -> 1, SHORT -> 2, INT -> 4, LONG -> 8,
      FLOAT -> 4, DOUBLE -> 8, COMPACT_DECIMAL(15, 10) -> 8, LARGE_DECIMAL(20, 10) -> 12,
      STRING -> 8, BINARY -> 16, STRUCT_TYPE -> 20, ARRAY_TYPE -> 16, MAP_TYPE -> 32)

    checks.foreach { case (columnType, expectedSize) =>
      assertResult(expectedSize, s"Wrong defaultSize for $columnType") {
        columnType.defaultSize
      }
    }
  }

  test("actualSize") {
    def checkActualSize(
        columnType: ColumnType[_],
        value: Any,
        expected: Int): Unit = {

      assertResult(expected, s"Wrong actualSize for $columnType") {
        val row = new GenericMutableRow(1)
        row.update(0, CatalystTypeConverters.convertToCatalyst(value))
        columnType.actualSize(row, 0)
      }
    }

    checkActualSize(NULL, null, 0)
    checkActualSize(BOOLEAN, true, 1)
    checkActualSize(BYTE, Byte.MaxValue, 1)
    checkActualSize(SHORT, Short.MaxValue, 2)
    checkActualSize(INT, Int.MaxValue, 4)
    checkActualSize(LONG, Long.MaxValue, 8)
    checkActualSize(FLOAT, Float.MaxValue, 4)
    checkActualSize(DOUBLE, Double.MaxValue, 8)
    checkActualSize(STRING, "hello", 4 + "hello".getBytes("utf-8").length)
    checkActualSize(BINARY, Array.fill[Byte](4)(0.toByte), 4 + 4)
    checkActualSize(COMPACT_DECIMAL(15, 10), Decimal(0, 15, 10), 8)
    checkActualSize(LARGE_DECIMAL(20, 10), Decimal(0, 20, 10), 5)
    checkActualSize(ARRAY_TYPE, Array[Any](1), 16)
    checkActualSize(MAP_TYPE, Map(1 -> "a"), 25)
    checkActualSize(STRUCT_TYPE, Row("hello"), 28)
  }

  testNativeColumnType(BOOLEAN)
  testNativeColumnType(BYTE)
  testNativeColumnType(SHORT)
  testNativeColumnType(INT)
  testNativeColumnType(LONG)
  testNativeColumnType(FLOAT)
  testNativeColumnType(DOUBLE)
  testNativeColumnType(COMPACT_DECIMAL(15, 10))
  testNativeColumnType(STRING)

  testColumnType(NULL)
  testColumnType(BINARY)
  testColumnType(LARGE_DECIMAL(20, 10))
  testColumnType(STRUCT_TYPE)
  testColumnType(ARRAY_TYPE)
  testColumnType(MAP_TYPE)

  def testNativeColumnType[T <: AtomicType](columnType: NativeColumnType[T]): Unit = {
    testColumnType[T#InternalType](columnType)
  }

  def testColumnType[JvmType](columnType: ColumnType[JvmType]): Unit = {

    val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
    val seq = (0 until 4).map(_ => makeRandomValue(columnType))
    val converter = CatalystTypeConverters.createToScalaConverter(columnType.dataType)

    test(s"$columnType append/extract") {
      buffer.rewind()
      seq.foreach(columnType.append(_, buffer))

      buffer.rewind()
      seq.foreach { expected =>
        logInfo("buffer = " + buffer + ", expected = " + expected)
        val extracted = columnType.extract(buffer)
        assert(
          converter(expected) === converter(extracted),
          "Extracted value didn't equal to the original one. " +
            hexDump(expected) + " != " + hexDump(extracted) +
            ", buffer = " + dumpBuffer(buffer.duplicate().rewind().asInstanceOf[ByteBuffer]))
      }
    }
  }

  private def hexDump(value: Any): String = {
    if (value == null) {
      ""
    } else {
      value.toString.map(ch => Integer.toHexString(ch & 0xffff)).mkString(" ")
    }
  }

  private def dumpBuffer(buff: ByteBuffer): Any = {
    val sb = new StringBuilder()
    while (buff.hasRemaining) {
      val b = buff.get()
      sb.append(Integer.toHexString(b & 0xff)).append(' ')
    }
    if (sb.nonEmpty) sb.setLength(sb.length - 1)
    sb.toString()
  }

  test("column type for decimal types with different precision") {
    (1 to 18).foreach { i =>
      assertResult(COMPACT_DECIMAL(i, 0)) {
        ColumnType(DecimalType(i, 0))
      }
    }

    assertResult(LARGE_DECIMAL(19, 0)) {
      ColumnType(DecimalType(19, 0))
    }
  }
}
