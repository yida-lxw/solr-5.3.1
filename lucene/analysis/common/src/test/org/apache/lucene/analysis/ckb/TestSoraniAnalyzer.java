package org.apache.lucene.analysis.ckb;

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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.BaseTokenStreamTestCase;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.util.Version;

/**
 * Test the Sorani analyzer
 */
public class TestSoraniAnalyzer extends BaseTokenStreamTestCase {
  
  /**
   * This test fails with NPE when the stopwords file is missing in classpath
   */
  public void testResourcesAvailable() {
    new SoraniAnalyzer().close();
  }
  
  public void testStopwords() throws IOException {
    Analyzer a = new SoraniAnalyzer();
    assertAnalyzesTo(a, "ئەم پیاوە", new String[] {"پیاو"});
    a.close();
  }
  
  public void testCustomStopwords() throws IOException {
    Analyzer a = new SoraniAnalyzer(CharArraySet.EMPTY_SET);
    assertAnalyzesTo(a, "ئەم پیاوە", 
        new String[] {"ئەم", "پیاو"});
    a.close();
  }
  
  public void testReusableTokenStream() throws IOException {
    Analyzer a = new SoraniAnalyzer();
    assertAnalyzesTo(a, "پیاوە", new String[] {"پیاو"});
    assertAnalyzesTo(a, "پیاو", new String[] {"پیاو"});
    a.close();
  }
  
  public void testWithStemExclusionSet() throws IOException {
    CharArraySet set = new CharArraySet(1, true);
    set.add("پیاوە");
    Analyzer a = new SoraniAnalyzer(CharArraySet.EMPTY_SET, set);
    assertAnalyzesTo(a, "پیاوە", new String[] { "پیاوە" });
    a.close();
  }
  
  /** blast some random strings through the analyzer */
  public void testRandomStrings() throws Exception {
    Analyzer a = new SoraniAnalyzer();
    checkRandomData(random(), a, 1000*RANDOM_MULTIPLIER);
    a.close();
  }

  public void testBackcompat40() throws IOException {
    SoraniAnalyzer a = new SoraniAnalyzer();
    a.setVersion(Version.LUCENE_4_6_1);
    // this is just a test to see the correct unicode version is being used, not actually testing hebrew
    assertAnalyzesTo(a, "א\"א", new String[] {"א", "א"});
  }
}
