package org.apache.lucene.bkdtree;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.GeoUtils;
import org.apache.lucene.util.ToStringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/** Finds all previously indexed points that fall within the specified polygon.
 *
 *  <p>The field must be indexed with {@link BKDTreeDocValuesFormat}, and {@link BKDPointField} added per document.
 *
 *  <p>Because this implementation cannot intersect each cell with the polygon, it will be costly especially for large polygons, as every
 *   possible point must be checked.
 *
 *  <p><b>NOTE</b>: for fastest performance, this allocates FixedBitSet(maxDoc) for each segment.  The score of each hit is the query boost.
 *
 * @lucene.experimental */

public class BKDPointInPolygonQuery extends Query {
  final String field;
  final double minLat;
  final double maxLat;
  final double minLon;
  final double maxLon;
  final double[] polyLats;
  final double[] polyLons;

  /** The lats/lons must be clockwise or counter-clockwise. */
  public BKDPointInPolygonQuery(String field, double[] polyLats, double[] polyLons) {
    this.field = field;
    if (polyLats.length != polyLons.length) {
      throw new IllegalArgumentException("polyLats and polyLons must be equal length");
    }
    if (polyLats.length < 4) {
      throw new IllegalArgumentException("at least 4 polygon points required");
    }
    if (polyLats[0] != polyLats[polyLats.length-1]) {
      throw new IllegalArgumentException("first and last points of the polygon must be the same (it must close itself): polyLats[0]=" + polyLats[0] + " polyLats[" + (polyLats.length-1) + "]=" + polyLats[polyLats.length-1]);
    }
    if (polyLons[0] != polyLons[polyLons.length-1]) {
      throw new IllegalArgumentException("first and last points of the polygon must be the same (it must close itself): polyLons[0]=" + polyLons[0] + " polyLons[" + (polyLons.length-1) + "]=" + polyLons[polyLons.length-1]);
    }

    this.polyLats = polyLats;
    this.polyLons = polyLons;

    // TODO: we could also compute the maximal innner bounding box, to make relations faster to compute?

    double minLon = Double.POSITIVE_INFINITY;
    double minLat = Double.POSITIVE_INFINITY;
    double maxLon = Double.NEGATIVE_INFINITY;
    double maxLat = Double.NEGATIVE_INFINITY;
    for(int i=0;i<polyLats.length;i++) {
      double lat = polyLats[i];
      if (BKDTreeWriter.validLat(lat) == false) {
        throw new IllegalArgumentException("polyLats[" + i + "]=" + lat + " is not a valid latitude");
      }
      minLat = Math.min(minLat, lat);
      maxLat = Math.max(maxLat, lat);
      double lon = polyLons[i];
      if (BKDTreeWriter.validLon(lon) == false) {
        throw new IllegalArgumentException("polyLons[" + i + "]=" + lat + " is not a valid longitude");
      }
      minLon = Math.min(minLon, lon);
      maxLon = Math.max(maxLon, lon);
    }
    this.minLon = minLon;
    this.maxLon = maxLon;
    this.minLat = minLat;
    this.maxLat = maxLat;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {

    // I don't use RandomAccessWeight here: it's no good to approximate with "match all docs"; this is an inverted structure and should be
    // used in the first pass:

    // TODO: except that the polygon verify is costly!  The approximation should be all docs in all overlapping cells, and matches() should
    // then check the polygon

    return new Weight(this) {
      private float queryNorm;
      private float queryWeight;

      @Override
      public void extractTerms(Set<Term> terms) {
      }

      @Override
      public float getValueForNormalization() throws IOException {
        queryWeight = getBoost();
        return queryWeight * queryWeight;
      }

      @Override
      public void normalize(float norm, float topLevelBoost) {
        queryNorm = norm * topLevelBoost;
        queryWeight *= queryNorm;
      }

      @Override
      public Explanation explain(LeafReaderContext context, int doc) throws IOException {
        final Scorer s = scorer(context);
        final boolean exists = s != null && s.advance(doc) == doc;

        if (exists) {
          return Explanation.match(queryWeight, BKDPointInPolygonQuery.this.toString() + ", product of:",
              Explanation.match(getBoost(), "boost"), Explanation.match(queryNorm, "queryNorm"));
        } else {
          return Explanation.noMatch(BKDPointInPolygonQuery.this.toString() + " doesn't match id " + doc);
        }
      }

      @Override
      public Scorer scorer(LeafReaderContext context) throws IOException {
        LeafReader reader = context.reader();
        SortedNumericDocValues sdv = reader.getSortedNumericDocValues(field);
        if (sdv == null) {
          // No docs in this segment had this field
          return null;
        }

        if (sdv instanceof BKDTreeSortedNumericDocValues == false) {
          throw new IllegalStateException("field \"" + field + "\" was not indexed with BKDTreeDocValuesFormat: got: " + sdv);
        }
        BKDTreeSortedNumericDocValues treeDV = (BKDTreeSortedNumericDocValues) sdv;
        BKDTreeReader tree = treeDV.getBKDTreeReader();
        
        // TODO: make this more efficient: as we recurse the BKD tree we should check whether the
        // bbox we are recursing into intersects our shape; Apache SIS may have (non-GPL!) code to do this?
        DocIdSet result = tree.intersect(minLat, maxLat, minLon, maxLon,
                                         new BKDTreeReader.LatLonFilter() {
                                           @Override
                                           public boolean accept(double lat, double lon) {
                                             return GeoUtils.pointInPolygon(polyLons, polyLats, lat, lon);
                                           }

                                           @Override
                                           public BKDTreeReader.Relation compare(double cellLatMin, double cellLatMax, double cellLonMin, double cellLonMax) {
                                             if (GeoUtils.rectWithinPoly(cellLonMin, cellLatMin, cellLonMax, cellLatMax,
                                                                         polyLons, polyLats,
                                                                         minLon, minLat, maxLon, maxLat)) {
                                               return BKDTreeReader.Relation.INSIDE;
                                             } else if (GeoUtils.rectCrossesPoly(cellLonMin, cellLatMin, cellLonMax, cellLatMax,
                                                                                 polyLons, polyLats,
                                                                                 minLon, minLat, maxLon, maxLat)) {
                                               return BKDTreeReader.Relation.CROSSES;
                                             } else {
                                               return BKDTreeReader.Relation.OUTSIDE;
                                             }
                                           }
                                         }, treeDV.delegate);

        final DocIdSetIterator disi = result.iterator();

        return new Scorer(this) {

          @Override
          public float score() throws IOException {
            return queryWeight;
          }

          @Override
          public int freq() throws IOException {
            return 1;
          }

          @Override
          public int docID() {
            return disi.docID();
          }

          @Override
          public int nextDoc() throws IOException {
            return disi.nextDoc();
          }

          @Override
          public int advance(int target) throws IOException {
            return disi.advance(target);
          }

          @Override
          public long cost() {
            return disi.cost();
          }
        };
      }
    };
  }

  @Override
  @SuppressWarnings({"unchecked","rawtypes"})
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    if (!super.equals(o)) return false;

    BKDPointInPolygonQuery that = (BKDPointInPolygonQuery) o;

    if (Arrays.equals(polyLons, that.polyLons) == false) {
      return false;
    }
    if (Arrays.equals(polyLats, that.polyLats) == false) {
      return false;
    }

    return true;
  }

  @Override
  public final int hashCode() {
    int result = super.hashCode();
    result = 31 * result + Arrays.hashCode(polyLons);
    result = 31 * result + Arrays.hashCode(polyLats);
    return result;
  }

  @Override
  public String toString(String field) {
    final StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName());
    sb.append(':');
    if (this.field.equals(field) == false) {
      sb.append(" field=");
      sb.append(this.field);
      sb.append(':');
    }
    sb.append(" Points: ");
    for (int i=0; i<polyLons.length; ++i) {
      sb.append("[")
        .append(polyLons[i])
        .append(", ")
        .append(polyLats[i])
        .append("] ");
    }
    sb.append(ToStringUtils.boost(getBoost()));
    return sb.toString();
  }
}
