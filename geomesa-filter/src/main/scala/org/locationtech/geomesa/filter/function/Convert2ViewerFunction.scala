/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.locationtech.geomesa.filter.function

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.{ByteBuffer, ByteOrder}

import com.vividsolutions.jts.geom.{Geometry, Point}
import org.geotools.data.Base64
import org.geotools.filter.FunctionExpressionImpl
import org.geotools.filter.capability.FunctionNameImpl
import org.geotools.filter.capability.FunctionNameImpl._

class Convert2ViewerFunction
  extends FunctionExpressionImpl(
    new FunctionNameImpl(
      "convert2viewer",
      classOf[String],
      parameter("id", classOf[String]),
      parameter("geom", classOf[Geometry]),
      parameter("dtg", classOf[Long])
    )) {

  override def evaluate(obj: scala.Any): String = {
    val id    = getExpression(0).evaluate(obj).asInstanceOf[String]
    val geom  = getExpression(1).evaluate(obj).asInstanceOf[Point]
    val dtg   = dtg2Long(getExpression(2).evaluate(obj))
    val values = BasicValues(geom.getY.toFloat, geom.getX.toFloat, dtg, id)
    Base64.encodeBytes(Convert2ViewerFunction.encodeToByteArray(values))
  }

  private def dtg2Long(d: Any): Long = d match {
    case l:    Long                           => l
    case jud:  java.util.Date                 => jud.getTime
    case inst: org.joda.time.ReadableInstant  => inst.getMillis
    case inst: org.opengis.temporal.Instant   => inst.getPosition.getDate.getTime
  }
}

object Convert2ViewerFunction {

  private val buffers: ThreadLocal[ByteBuffer] = new ThreadLocal[ByteBuffer] {
    override def initialValue = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    override def get = {
      val out = super.get
      out.clear() // ready for re-use
      out
    }
  }

  private val byteStreams: ThreadLocal[ByteArrayOutputStream] = new ThreadLocal[ByteArrayOutputStream] {
    override def initialValue = new ByteArrayOutputStream(24)
    override def get = {
      val out = super.get
      out.reset() // ready for re-use
      out
    }
  }

  /**
   * Reachback version is the simple version with an extra
   *
   * 64-bit quantity: label/reachback/whatever you want
   *
   * @param values
   */
  def encode(values: ExtendedValues, out: OutputStream): Unit = {
    val buf = buffers.get()
    put(buf, values.lat, values.lon, values.dtg, values.trackId)
    buf.putLong(values.label)
    out.write(buf.array(), 0, 24)
  }

  /**
   * Simple version:
   *
   * 32-bit int: track_id (a threading key that you can use to color (or not) the dots)
   * 32-bit int: seconds since the epoch
   * 32-bit float: latitude degrees (+/-90)
   * 32-bit float: longitude degrees (+/- 180)
   *
   * @param values
   */
  def encode(values: BasicValues, out: OutputStream): Unit = {
    val buf = buffers.get()
    put(buf, values.lat, values.lon, values.dtg, values.trackId)
    out.write(buf.array(), 0, 16)
  }

  def encodeToByteArray(values: ExtendedValues): Array[Byte] = {
    val out = byteStreams.get()
    encode(values, out)
    out.toByteArray
  }

  def encodeToByteArray(values: BasicValues): Array[Byte] = {
    val out = byteStreams.get()
    encode(values, out)
    out.toByteArray
  }

  /**
   * Fills in the basic values
   *
   * @param buffer
   * @param lat
   * @param lon
   * @param dtg
   * @param trackId
   */
  private def put(buffer: ByteBuffer, lat: Float, lon: Float, dtg: Long, trackId: String): Unit = {
    buffer.putInt(trackId.hashCode)
    buffer.putInt((dtg / 1000).toInt)
    buffer.putFloat(lat)
    buffer.putFloat(lon)
  }

  /**
   * Decodes a byte array
   *
   * @param encoded
   * @return
   */
  def decode(encoded: Array[Byte]): EncodedValues = {
    val buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
    val trackId = buf.getInt.toString
    val time = buf.getInt * 1000L
    val lat = buf.getFloat
    val lon = buf.getFloat
    if (encoded.length > 16) {
      val label = buf.getLong
      ExtendedValues(lat, lon, time, trackId, label)
    } else {
      BasicValues(lat, lon, time, trackId)
    }
  }
}

sealed trait EncodedValues {
  def lat: Float
  def lon: Float
  def dtg: Long
  def trackId: String
}

case class BasicValues(lat: Float, lon: Float, dtg: Long, trackId: String) extends EncodedValues

case class ExtendedValues(lat: Float, lon: Float, dtg: Long, trackId: String, label: Long)
    extends EncodedValues
