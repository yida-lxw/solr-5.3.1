package org.apache.lucene.search;

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

import java.io.IOException;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LuceneTestCase;

public class TestNeedsScores extends LuceneTestCase {
  Directory dir;
  IndexReader reader;
  IndexSearcher searcher;
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    dir = newDirectory();
    RandomIndexWriter iw = new RandomIndexWriter(random(), dir);
    for (int i = 0; i < 5; i++) {
      Document doc = new Document();
      doc.add(new TextField("field", "this is document " + i, Field.Store.NO));
      iw.addDocument(doc);
    }
    reader = iw.getReader();
    searcher = newSearcher(reader);
    iw.close();
  }

  @Override
  public void tearDown() throws Exception {
    IOUtils.close(reader, dir);
    super.tearDown();
  }

  /** prohibited clauses in booleanquery don't need scoring */
  public void testProhibitedClause() throws Exception {
    Query required = new TermQuery(new Term("field", "this"));
    Query prohibited = new TermQuery(new Term("field", "3"));
    BooleanQuery.Builder bq = new BooleanQuery.Builder();
    bq.add(new AssertNeedsScores(required, true), BooleanClause.Occur.MUST);
    bq.add(new AssertNeedsScores(prohibited, false), BooleanClause.Occur.MUST_NOT);
    assertEquals(4, searcher.search(bq.build(), 5).totalHits); // we exclude 3
  }
  
  /** nested inside constant score query */
  public void testConstantScoreQuery() throws Exception {
    Query term = new TermQuery(new Term("field", "this"));
    Query constantScore = new ConstantScoreQuery(new AssertNeedsScores(term, false));
    assertEquals(5, searcher.search(constantScore, 5).totalHits);
  }
  
  /** when converted to a filter */
  public void testQueryWrapperFilter() throws Exception {
    Query query = new MatchAllDocsQuery();
    Query term = new TermQuery(new Term("field", "this"));
    Filter filter = new QueryWrapperFilter(new AssertNeedsScores(term, false));
    assertEquals(5, searcher.search(new FilteredQuery(query, filter), 5).totalHits);
  }
  
  /** when not sorting by score */
  public void testSortByField() throws Exception {
    Query query = new AssertNeedsScores(new MatchAllDocsQuery(), false);
    assertEquals(5, searcher.search(query, 5, Sort.INDEXORDER).totalHits);
  }
  
  /** when sorting by score */
  public void testSortByScore() throws Exception {
    Query query = new AssertNeedsScores(new MatchAllDocsQuery(), true);
    assertEquals(5, searcher.search(query, 5, Sort.RELEVANCE).totalHits);
  }

  /** 
   * Wraps a query, checking that the needsScores param 
   * passed to Weight.scorer is the expected value.
   */
  static class AssertNeedsScores extends Query {
    final Query in;
    final boolean value;
    
    AssertNeedsScores(Query in, boolean value) {
      this.in = in;
      this.value = value;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, final boolean needsScores) throws IOException {
      final Weight w = in.createWeight(searcher, needsScores);
      return new Weight(AssertNeedsScores.this) {
        @Override
        public void extractTerms(Set<Term> terms) {
          w.extractTerms(terms);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
          return w.explain(context, doc);
        }

        @Override
        public float getValueForNormalization() throws IOException {
          return w.getValueForNormalization();
        }

        @Override
        public void normalize(float norm, float topLevelBoost) {
          w.normalize(norm, topLevelBoost);
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
          assertEquals("query=" + in, value, needsScores);
          return w.scorer(context);
        }
      };
    }

    @Override
    public Query rewrite(IndexReader reader) throws IOException {
      Query in2 = in.rewrite(reader);
      if (in2 == in) {
        return this;
      } else {
        return new AssertNeedsScores(in2, value);
      }
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = super.hashCode();
      result = prime * result + ((in == null) ? 0 : in.hashCode());
      result = prime * result + (value ? 1231 : 1237);
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!super.equals(obj)) return false;
      if (getClass() != obj.getClass()) return false;
      AssertNeedsScores other = (AssertNeedsScores) obj;
      if (in == null) {
        if (other.in != null) return false;
      } else if (!in.equals(other.in)) return false;
      if (value != other.value) return false;
      return true;
    }

    @Override
    public String toString(String field) {
      return "asserting(" + in.toString(field) + ")";
    }
  }
}
