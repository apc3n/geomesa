/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.core.iterators

import java.util.{HashSet => JHashSet}

import com.typesafe.scalalogging.slf4j.Logging
import org.apache.accumulo.core.data.{ByteSequence, Key, Range, Value}
import org.apache.accumulo.core.iterators.{IteratorEnvironment, SortedKeyValueIterator}
import org.apache.hadoop.io.Text
import org.locationtech.geomesa.core.data.tables.SpatioTemporalTable
import org.locationtech.geomesa.core.index._

case class Attribute(name: Text, value: Text)

/**
 * Remember that we maintain two sources:  one for index records (that determines
 * whether the point is inside the search polygon), and one for data records (that
 * can consist of multiple data rows per index row, one data-row per attribute).
 * Each time the index source advances, we need to reposition the data source.
 *
 * This iterator returns as its nextKey and nextValue responses the key and value
 * from the DATA iterator, not from the INDEX iterator.  The assumption is that
 * the data rows are what we care about; that we do not care about the index
 * rows that merely helped us find the data rows quickly.
 *
 * The other trick to remember about iterators is that they essentially pre-fetch
 * data.  "hasNext" really means, "was there a next record that you already found".
 */
class SpatioTemporalIntersectingIterator
    extends SortedKeyValueIterator[Key, Value]
    with WrappedFeatureType
    with WrappedFeatureDecoder
    with WrappedSTFilter
    with WrappedEcqlFilter
    with WrappedTransform
    with InMemoryDeduplication
    with Logging {

  var topKey: Option[Key] = None
  var topValue: Option[Value] = None
  var source: SortedKeyValueIterator[Key, Value] = null

  override def init(source: SortedKeyValueIterator[Key, Value],
           options: java.util.Map[String, String],
           env: IteratorEnvironment) {

    TServerClassLoader.initClassLoader(logger)

    initFeatureType(options)
    initDecoder(featureType, options)
    initSTFilter(featureType, options)
    initEcqlFilter(options)
    initTransform(featureType, options)
    initDeduplication(featureType, options)

    this.source = source.deepCopy(env)
  }

  override def hasTop = topKey.isDefined

  override def getTopKey = topKey.orNull

  override def getTopValue = topValue.orNull

  /**
   * Seeks to the start of a range and fetches the top key/value
   *
   * @param range
   * @param columnFamilies
   * @param inclusive
   */
  override def seek(range: Range, columnFamilies: java.util.Collection[ByteSequence], inclusive: Boolean) {
    // move the source iterator to the right starting spot
    source.seek(range, columnFamilies, inclusive)
    findTop()
  }

  /**
   * Reads the next qualifying key/value
   */
  override def next() = findTop()

  /**
   * Advances the index-iterator to the next qualifying entry
   */
  def findTop() {

    // clear out the reference to the last entry
    topKey = None
    topValue = None

    // loop while there is more data and we haven't matched our filter
    while (topValue.isEmpty && source.hasTop) {

      val indexKey = source.getTopKey

      if (SpatioTemporalTable.isIndexEntry(indexKey)) { // if this is a data entry, skip it
        // only decode it if we have a filter to evaluate
        // the value contains the full-resolution geometry and time plus feature ID
        lazy val decodedValue = IndexEntry.decodeIndexValue(source.getTopValue)

        // evaluate the filter checks, in least to most expensive order
        val meetsIndexFilters = checkUniqueId.forall(fn => fn(decodedValue.id)) &&
            wrappedSTFilter.forall(fn => fn(decodedValue.geom, decodedValue.dtgMillis))

        if (meetsIndexFilters) { // we hit a valid geometry, date and id
          // we increment the source iterator, which should point to a data entry
          source.next()
          if (source.hasTop) {
            val dataKey = source.getTopKey
            if (SpatioTemporalTable.isDataEntry(dataKey)) {
              val dataValue = source.getTopValue
              lazy val decodedFeature = featureDecoder.decode(dataValue)
              val meetsEcqlFilter = wrappedEcqlFilter.forall(fn => fn(decodedFeature))
              if (meetsEcqlFilter) {
                // update the key and value
                // copy the key because reusing it is UNSAFE
                topKey = Some(new Key(dataKey))
                // apply any transform here
                topValue = wrappedTransform.map(fn => new Value(fn(decodedFeature)))
                    .orElse(Some(new Value(dataValue)))
              }
            } else {
              logger.error(s"Could not find the data key corresponding to index key '$indexKey' - " +
                  "there is no data entry.")
            }
          } else {
            logger.error(s"Could not find the data key corresponding to index key '$indexKey' - " +
                "there are no more entries")
          }
        }
        // TODO we have a lot of nested ifs here, try to clean it up
      }

      // increment the underlying iterator
      source.next()
    }
  }

  override def deepCopy(env: IteratorEnvironment) =
    throw new UnsupportedOperationException("STII does not support deepCopy.")
}
