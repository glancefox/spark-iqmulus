/*
 * Copyright 2015 IGN
 *
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

package fr.ign.spark.iqmulus

import org.apache.spark.sql.{ SQLContext, DataFrameReader, DataFrameWriter, DataFrame }
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.Row

package object las {

  /**
   * Adds a method, `las`, to DataFrameWriter that allows you to write las files using
   * the DataFileWriter
   */
  implicit class LasDataFrameWriter(writer: DataFrameWriter) {
    def las: String => Unit = writer.format("fr.ign.spark.iqmulus.las").save
    def lasformat(format: Byte): LasDataFrameWriter = writer.option("lasformat", format.toString)
    def major(m: Byte): LasDataFrameWriter = writer.option("minor", m.toString)
    def minor(m: Byte): LasDataFrameWriter = writer.option("major", m.toString)
    def version(v: String): LasDataFrameWriter = writer.option("version", v)
  }

  /**
   * Adds a method, `las`, to DataFrameReader that allows you to read las files using
   * the DataFileReade
   */
  implicit class LasDataFrameReader(reader: DataFrameReader) {
    def las: String => DataFrame = reader.format("fr.ign.spark.iqmulus.las").load
  }

  implicit class LasDataFrame(df: DataFrame) {
    def saveAsLas(
      location: String,
      formatOpt: Option[Byte] = None,
      version: Version = Version(),
      scale: Array[Double] = Array(0.01, 0.01, 0.01),
      offset: Array[Double] = Array(0, 0, 0)
    ) = {
      val format = formatOpt.getOrElse(LasHeader.formatFromSchema(df.schema))
      val schema = LasHeader.schema(format) // no user types for now
      val cols = schema.fieldNames.intersect(df.schema.fieldNames)
      val saver = (key: Int, iter: Iterator[Row]) =>
        Iterator(iter.saveAsLas(s"$location/$key.las", schema, format, scale, offset, version))
      df.select(cols.head, cols.tail: _*).rdd.mapPartitionsWithIndex(saver, true).collect
    }
  }

  implicit class LasRowIterator(iter: Iterator[Row]) {
    def saveAsLas(
      filename: String, schema: StructType, format: Byte,
      scale: Array[Double], offset: Array[Double], version: Version = Version()
    ) = {
      // materialize the partition to access it in a single pass, TODO workaround that 
      val rows = iter.toArray
      val count = rows.length.toLong
      val pmin = Array.fill[Double](3)(Double.PositiveInfinity)
      val pmax = Array.fill[Double](3)(Double.NegativeInfinity)
      val countByReturn = Array.fill[Long](15)(0)
      rows.foreach { row =>
        val x = offset(0) + scale(0) * row.getAs[Int]("x").toDouble
        val y = offset(1) + scale(1) * row.getAs[Int]("y").toDouble
        val z = offset(2) + scale(2) * row.getAs[Int]("z").toDouble
        val ret = row.getAs[Byte]("flags") & 0x3
        countByReturn(ret) += 1
        pmin(0) = Math.min(pmin(0), x)
        pmin(1) = Math.min(pmin(1), y)
        pmin(2) = Math.min(pmin(2), z)
        pmax(0) = Math.max(pmax(0), x)
        pmax(1) = Math.max(pmax(1), y)
        pmax(2) = Math.max(pmax(2), z)
      }
      val path = new org.apache.hadoop.fs.Path(filename)
      val fs = path.getFileSystem(new org.apache.hadoop.conf.Configuration)
      val f = fs.create(path)
      val header = new LasHeader(filename, format, count, pmin, pmax, scale, offset,
        version = version, pdr_return_nb = countByReturn)
      val dos = new java.io.DataOutputStream(f);
      header.write(dos)
      val ros = new RowOutputStream(dos, littleEndian = true, schema)
      rows.foreach(ros.write)
      dos.close
      header
    }
  }
}
