package org.apache.solr.client.solrj.io.stream;

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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionParser;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.cloud.AbstractZkTestCase;
import org.apache.solr.common.SolrInputDocument;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  All base tests will be done with CloudSolrStream. Under the covers CloudSolrStream uses SolrStream so
 *  SolrStream will get fully exercised through these tests.
 *
 **/

@Slow
@LuceneTestCase.SuppressCodecs({"Lucene3x", "Lucene40","Lucene41","Lucene42","Lucene45"})
public class StreamExpressionTest extends AbstractFullDistribZkTestBase {

  private static final String SOLR_HOME = getFile("solrj" + File.separator + "solr").getAbsolutePath();

  static {
    schemaString = "schema-streaming.xml";
  }

  @BeforeClass
  public static void beforeSuperClass() {
    AbstractZkTestCase.SOLRHOME = new File(SOLR_HOME());
  }

  @AfterClass
  public static void afterSuperClass() {

  }

  protected String getCloudSolrConfig() {
    return "solrconfig-streaming.xml";
  }


  @Override
  public String getSolrHome() {
    return SOLR_HOME;
  }

  public static String SOLR_HOME() {
    return SOLR_HOME;
  }

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    // we expect this time of exception as shards go up and down...
    //ignoreException(".*");

    System.setProperty("numShards", Integer.toString(sliceCount));
  }

  @Override
  @After
  public void tearDown() throws Exception {
    super.tearDown();
    resetExceptionIgnores();
  }

  public StreamExpressionTest() {
    super();
    sliceCount = 2;
  }

  @Test
  public void testAll() throws Exception{
    assertNotNull(cloudClient);

    handle.clear();
    handle.put("timestamp", SKIPVAL);

    waitForThingsToLevelOut(30);

    del("*:*");
    commit();
    
    testCloudSolrStream();
    testCloudSolrStreamWithZkHost();
    testMergeStream();
    testRankStream();
    testReducerStream();
    testUniqueStream();
    testParallelUniqueStream();
    testParallelReducerStream();
    testParallelRankStream();
    testParallelMergeStream();
  }

  private void testCloudSolrStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello1", "a_i", "1", "a_f", "1");
    commit();

    StreamFactory factory = new StreamFactory().withCollectionZkHost("collection1", zkServer.getZkAddress());
    StreamExpression expression;
    CloudSolrStream stream;
    List<Tuple> tuples;
    
    // Basic test
    expression = StreamExpressionParser.parse("search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\")");
    stream = new CloudSolrStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 5);
    assertOrder(tuples, 0,2,1,3,4);
    assertLong(tuples.get(0),"a_i", 0);

    // Basic w/aliases
    expression = StreamExpressionParser.parse("search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\", aliases=\"a_i=alias.a_i, a_s=name\")");
    stream = new CloudSolrStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 5);
    assertOrder(tuples, 0,2,1,3,4);
    assertLong(tuples.get(0),"alias.a_i", 0);
    assertString(tuples.get(0),"name", "hello0");    

    // Basic filtered test
    expression = StreamExpressionParser.parse("search(collection1, q=\"id:(0 3 4)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\")");
    stream = new CloudSolrStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 3);
    assertOrder(tuples, 0,3,4);
    assertLong(tuples.get(1),"a_i", 3);
    
    del("*:*");
    commit();
  }

  private void testCloudSolrStreamWithZkHost() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello1", "a_i", "1", "a_f", "1");
    commit();

    StreamFactory factory = new StreamFactory();
    StreamExpression expression;
    CloudSolrStream stream;
    List<Tuple> tuples;
    
    // Basic test
    expression = StreamExpressionParser.parse("search(collection1, zkHost=" + zkServer.getZkAddress() + ", q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\")");
    stream = new CloudSolrStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 5);
    assertOrder(tuples, 0,2,1,3,4);
    assertLong(tuples.get(0),"a_i", 0);

    // Basic w/aliases
    expression = StreamExpressionParser.parse("search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\", aliases=\"a_i=alias.a_i, a_s=name\", zkHost=" + zkServer.getZkAddress() + ")");
    stream = new CloudSolrStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 5);
    assertOrder(tuples, 0,2,1,3,4);
    assertLong(tuples.get(0),"alias.a_i", 0);
    assertString(tuples.get(0),"name", "hello0");    

    // Basic filtered test
    expression = StreamExpressionParser.parse("search(collection1, q=\"id:(0 3 4)\", fl=\"id,a_s,a_i,a_f\", zkHost=" + zkServer.getZkAddress() + ", sort=\"a_f asc, a_i asc\")");
    stream = new CloudSolrStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 3);
    assertOrder(tuples, 0,3,4);
    assertLong(tuples.get(1),"a_i", 3);
    
    del("*:*");
    commit();
  }

  
  private void testUniqueStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello1", "a_i", "1", "a_f", "1");
    commit();

    StreamExpression expression;
    TupleStream stream;
    List<Tuple> tuples;
    
    StreamFactory factory = new StreamFactory()
      .withCollectionZkHost("collection1", zkServer.getZkAddress())
      .withStreamFunction("search", CloudSolrStream.class)
      .withStreamFunction("unique", UniqueStream.class);
    
    // Basic test
    expression = StreamExpressionParser.parse("unique(search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\"), over=\"a_f asc\")");
    stream = new UniqueStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 4);
    assertOrder(tuples, 0, 1, 3, 4);

    // Basic test desc
    expression = StreamExpressionParser.parse("unique(search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f desc, a_i desc\"), over=\"a_f desc\")");
    stream = new UniqueStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 4);
    assertOrder(tuples, 4,3,1,2);
    
    // Basic w/multi comp
    expression = StreamExpressionParser.parse("unique(search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\"), over=\"a_f asc, a_i asc\")");
    stream = new UniqueStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 5);
    assertOrder(tuples, 0,2,1,3,4);
    
    // full factory w/multi comp
    stream = factory.constructStream("unique(search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\"), over=\"a_f asc, a_i asc\")");
    tuples = getTuples(stream);
    
    assert(tuples.size() == 5);
    assertOrder(tuples, 0, 2, 1, 3, 4);
    
    del("*:*");
    commit();
  }
  
  private void testMergeStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello1", "a_i", "1", "a_f", "1");
    commit();

    StreamExpression expression;
    TupleStream stream;
    List<Tuple> tuples;
    
    StreamFactory factory = new StreamFactory()
      .withCollectionZkHost("collection1", zkServer.getZkAddress())
      .withStreamFunction("search", CloudSolrStream.class)
      .withStreamFunction("unique", UniqueStream.class)
      .withStreamFunction("merge", MergeStream.class);
    
    // Basic test
    expression = StreamExpressionParser.parse("merge("
                                                + "search(collection1, q=\"id:(0 3 4)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc\"),"
                                                + "search(collection1, q=\"id:(1)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc\"),"
                                                + "on=\"a_f asc\")");
    stream = new MergeStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 4);
    assertOrder(tuples, 0,1,3,4);

    // Basic test desc
    expression = StreamExpressionParser.parse("merge("
                                              + "search(collection1, q=\"id:(0 3 4)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f desc\"),"
                                              + "search(collection1, q=\"id:(1)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f desc\"),"
                                              + "on=\"a_f desc\")");
    stream = new MergeStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 4);
    assertOrder(tuples, 4,3,1,0);
    
    // Basic w/multi comp
    expression = StreamExpressionParser.parse("merge("
                                              + "search(collection1, q=\"id:(0 3 4)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_s asc\"),"
                                              + "search(collection1, q=\"id:(1 2)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_s asc\"),"
                                              + "on=\"a_f asc, a_s asc\")");
    stream = new MergeStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 5);
    assertOrder(tuples, 0,2,1,3,4);
    
    // full factory w/multi comp
    stream = factory.constructStream("merge("
                                    + "search(collection1, q=\"id:(0 3 4)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_s asc\"),"
                                    + "search(collection1, q=\"id:(1 2)\", fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_s asc\"),"
                                    + "on=\"a_f asc, a_s asc\")");
    tuples = getTuples(stream);
    
    assert(tuples.size() == 5);
    assertOrder(tuples, 0,2,1,3,4);
    
    del("*:*");
    commit();
  }
  
  private void testRankStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello1", "a_i", "1", "a_f", "1");
    commit();

    StreamExpression expression;
    TupleStream stream;
    List<Tuple> tuples;
    
    StreamFactory factory = new StreamFactory()
      .withCollectionZkHost("collection1", zkServer.getZkAddress())
      .withStreamFunction("search", CloudSolrStream.class)
      .withStreamFunction("unique", UniqueStream.class)
      .withStreamFunction("top", RankStream.class);
    
    // Basic test
    expression = StreamExpressionParser.parse("top("
                                              + "n=3,"
                                              + "search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\"),"
                                              + "sort=\"a_f asc, a_i asc\")");
    stream = new RankStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 3);
    assertOrder(tuples, 0,2,1);

    // Basic test desc
    expression = StreamExpressionParser.parse("top("
                                              + "n=2,"
                                              + "unique("
                                              +   "search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f desc\"),"
                                              +   "over=\"a_f desc\"),"
                                              + "sort=\"a_f desc\")");
    stream = new RankStream(expression, factory);
    tuples = getTuples(stream);
    
    assert(tuples.size() == 2);
    assertOrder(tuples, 4,3);
    
    // full factory
    stream = factory.constructStream("top("
                                    + "n=4,"
                                    + "unique("
                                    +   "search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\"),"
                                    +   "over=\"a_f asc\"),"
                                    + "sort=\"a_f asc\")");
    tuples = getTuples(stream);
    
    assert(tuples.size() == 4);
    assertOrder(tuples, 0,1,3,4);
    
    del("*:*");
    commit();
  }
  
  private void testReducerStream() throws Exception{
    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "1");
    indexr(id, "2", "a_s", "hello0", "a_i", "2", "a_f", "2");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello0", "a_i", "1", "a_f", "5");
    indexr(id, "5", "a_s", "hello3", "a_i", "10", "a_f", "6");
    indexr(id, "6", "a_s", "hello4", "a_i", "11", "a_f", "7");
    indexr(id, "7", "a_s", "hello3", "a_i", "12", "a_f", "8");
    indexr(id, "8", "a_s", "hello3", "a_i", "13", "a_f", "9");
    indexr(id, "9", "a_s", "hello0", "a_i", "14", "a_f", "10");
    commit();
    
    StreamExpression expression;
    TupleStream stream;
    List<Tuple> tuples;
    Tuple t0, t1, t2;
    List<Map> maps0, maps1, maps2;
    
    StreamFactory factory = new StreamFactory()
      .withCollectionZkHost("collection1", zkServer.getZkAddress())
      .withStreamFunction("search", CloudSolrStream.class)
      .withStreamFunction("unique", UniqueStream.class)
      .withStreamFunction("top", RankStream.class)
      .withStreamFunction("group", ReducerStream.class);

    // basic
    expression = StreamExpressionParser.parse("group("
                                              + "search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_s asc, a_f asc\"),"
                                              + "by=\"a_s asc\")");
    stream = new ReducerStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 3);
    assertOrder(tuples, 0,3,4);

    t0 = tuples.get(0);
    maps0 = t0.getMaps();
    assertMaps(maps0, 0, 2,1, 9);

    t1 = tuples.get(1);
    maps1 = t1.getMaps();
    assertMaps(maps1, 3, 5, 7, 8);

    t2 = tuples.get(2);
    maps2 = t2.getMaps();
    assertMaps(maps2, 4, 6);
    
    // basic w/spaces
    expression = StreamExpressionParser.parse("group("
                                              + "search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_s asc, a_f       asc\"),"
                                              + "by=\"a_s asc\")");
    stream = new ReducerStream(expression, factory);
    tuples = getTuples(stream);

    assert(tuples.size() == 3);
    assertOrder(tuples, 0,3,4);

    t0 = tuples.get(0);
    maps0 = t0.getMaps();
    assertMaps(maps0, 0, 2,1, 9);

    t1 = tuples.get(1);
    maps1 = t1.getMaps();
    assertMaps(maps1, 3, 5, 7, 8);

    t2 = tuples.get(2);
    maps2 = t2.getMaps();
    assertMaps(maps2, 4, 6);

    del("*:*");
    commit();
  }

  private void testParallelUniqueStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello1", "a_i", "1", "a_f", "1");
    indexr(id, "5", "a_s", "hello1", "a_i", "10", "a_f", "1");
    indexr(id, "6", "a_s", "hello1", "a_i", "11", "a_f", "5");
    indexr(id, "7", "a_s", "hello1", "a_i", "12", "a_f", "5");
    indexr(id, "8", "a_s", "hello1", "a_i", "13", "a_f", "4");

    commit();

    String zkHost = zkServer.getZkAddress();
    StreamFactory streamFactory = new StreamFactory().withCollectionZkHost("collection1", zkServer.getZkAddress())
        .withStreamFunction("search", CloudSolrStream.class)
        .withStreamFunction("unique", UniqueStream.class)
        .withStreamFunction("top", RankStream.class)
        .withStreamFunction("group", ReducerStream.class)
        .withStreamFunction("parallel", ParallelStream.class);

    ParallelStream pstream = (ParallelStream)streamFactory.constructStream("parallel(collection1, unique(search(collection1, q=*:*, fl=\"id,a_s,a_i,a_f\", sort=\"a_f asc, a_i asc\", partitionKeys=\"a_f\"), over=\"a_f asc\"), workers=\"2\", zkHost=\""+zkHost+"\", sort=\"a_f asc\")");

    List<Tuple> tuples = getTuples(pstream);
    assert(tuples.size() == 5);
    assertOrder(tuples, 0,1,3,4,6);

    //Test the eofTuples

    Map<String,Tuple> eofTuples = pstream.getEofTuples();
    assert(eofTuples.size() == 2); //There should be an EOF tuple for each worker.

    del("*:*");
    commit();

  }

  private void testParallelReducerStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "1");
    indexr(id, "2", "a_s", "hello0", "a_i", "2", "a_f", "2");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello0", "a_i", "1", "a_f", "5");
    indexr(id, "5", "a_s", "hello3", "a_i", "10", "a_f", "6");
    indexr(id, "6", "a_s", "hello4", "a_i", "11", "a_f", "7");
    indexr(id, "7", "a_s", "hello3", "a_i", "12", "a_f", "8");
    indexr(id, "8", "a_s", "hello3", "a_i", "13", "a_f", "9");
    indexr(id, "9", "a_s", "hello0", "a_i", "14", "a_f", "10");

    commit();

    String zkHost = zkServer.getZkAddress();
    StreamFactory streamFactory = new StreamFactory().withCollectionZkHost("collection1", zkServer.getZkAddress())
        .withStreamFunction("search", CloudSolrStream.class)
        .withStreamFunction("unique", UniqueStream.class)
        .withStreamFunction("top", RankStream.class)
        .withStreamFunction("group", ReducerStream.class)
        .withStreamFunction("parallel", ParallelStream.class);

    ParallelStream pstream = (ParallelStream)streamFactory.constructStream("parallel(collection1, group(search(collection1, q=\"*:*\", fl=\"id,a_s,a_i,a_f\", sort=\"a_s asc,a_f asc\", partitionKeys=\"a_s\"), by=\"a_s asc\"), workers=\"2\", zkHost=\""+zkHost+"\", sort=\"a_s asc\")");

    List<Tuple> tuples = getTuples(pstream);

    assert(tuples.size() == 3);
    assertOrder(tuples, 0,3,4);

    Tuple t0 = tuples.get(0);
    List<Map> maps0 = t0.getMaps();
    assertMaps(maps0, 0, 2, 1, 9);

    Tuple t1 = tuples.get(1);
    List<Map> maps1 = t1.getMaps();
    assertMaps(maps1, 3, 5, 7, 8);

    Tuple t2 = tuples.get(2);
    List<Map> maps2 = t2.getMaps();
    assertMaps(maps2, 4, 6);

    //Test Descending with Ascending subsort

    pstream = (ParallelStream)streamFactory.constructStream("parallel(collection1, group(search(collection1, q=\"*:*\", fl=\"id,a_s,a_i,a_f\", sort=\"a_s desc,a_f asc\", partitionKeys=\"a_s\"), by=\"a_s desc\"), workers=\"2\", zkHost=\""+zkHost+"\", sort=\"a_s desc\")");

    tuples = getTuples(pstream);

    assert(tuples.size() == 3);
    assertOrder(tuples, 4,3,0);

    t0 = tuples.get(0);
    maps0 = t0.getMaps();
    assertMaps(maps0, 4, 6);


    t1 = tuples.get(1);
    maps1 = t1.getMaps();
    assertMaps(maps1, 3, 5, 7, 8);


    t2 = tuples.get(2);
    maps2 = t2.getMaps();
    assertMaps(maps2, 0, 2, 1, 9);



    del("*:*");
    commit();
  }

  private void testParallelRankStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "5", "a_s", "hello1", "a_i", "5", "a_f", "1");
    indexr(id, "6", "a_s", "hello1", "a_i", "6", "a_f", "1");
    indexr(id, "7", "a_s", "hello1", "a_i", "7", "a_f", "1");
    indexr(id, "8", "a_s", "hello1", "a_i", "8", "a_f", "1");
    indexr(id, "9", "a_s", "hello1", "a_i", "9", "a_f", "1");
    indexr(id, "10", "a_s", "hello1", "a_i", "10", "a_f", "1");

    commit();

    String zkHost = zkServer.getZkAddress();
    StreamFactory streamFactory = new StreamFactory().withCollectionZkHost("collection1", zkServer.getZkAddress())
        .withStreamFunction("search", CloudSolrStream.class)
        .withStreamFunction("unique", UniqueStream.class)
        .withStreamFunction("top", RankStream.class)
        .withStreamFunction("group", ReducerStream.class)
        .withStreamFunction("parallel", ParallelStream.class);

    ParallelStream pstream = (ParallelStream)streamFactory.constructStream("parallel(collection1, top(search(collection1, q=\"*:*\", fl=\"id,a_s,a_i\", sort=\"a_i asc\", partitionKeys=\"a_i\"), n=\"11\", sort=\"a_i desc\"), workers=\"2\", zkHost=\""+zkHost+"\", sort=\"a_i desc\")");

    List<Tuple> tuples = getTuples(pstream);

    assert(tuples.size() == 10);
    assertOrder(tuples, 10,9,8,7,6,5,4,3,2,0);

    del("*:*");
    commit();
  }

  private void testParallelMergeStream() throws Exception {

    indexr(id, "0", "a_s", "hello0", "a_i", "0", "a_f", "0");
    indexr(id, "2", "a_s", "hello2", "a_i", "2", "a_f", "0");
    indexr(id, "3", "a_s", "hello3", "a_i", "3", "a_f", "3");
    indexr(id, "4", "a_s", "hello4", "a_i", "4", "a_f", "4");
    indexr(id, "1", "a_s", "hello1", "a_i", "1", "a_f", "1");
    indexr(id, "5", "a_s", "hello0", "a_i", "10", "a_f", "0");
    indexr(id, "6", "a_s", "hello2", "a_i", "8", "a_f", "0");
    indexr(id, "7", "a_s", "hello3", "a_i", "7", "a_f", "3");
    indexr(id, "8", "a_s", "hello4", "a_i", "11", "a_f", "4");
    indexr(id, "9", "a_s", "hello1", "a_i", "100", "a_f", "1");

    commit();

    String zkHost = zkServer.getZkAddress();
    StreamFactory streamFactory = new StreamFactory().withCollectionZkHost("collection1", zkServer.getZkAddress())
        .withStreamFunction("search", CloudSolrStream.class)
        .withStreamFunction("unique", UniqueStream.class)
        .withStreamFunction("top", RankStream.class)
        .withStreamFunction("group", ReducerStream.class)
        .withStreamFunction("merge", MergeStream.class)
        .withStreamFunction("parallel", ParallelStream.class);

    //Test ascending
    ParallelStream pstream = (ParallelStream)streamFactory.constructStream("parallel(collection1, merge(search(collection1, q=\"id:(4 1 8 7 9)\", fl=\"id,a_s,a_i\", sort=\"a_i asc\", partitionKeys=\"a_i\"), search(collection1, q=\"id:(0 2 3 6)\", fl=\"id,a_s,a_i\", sort=\"a_i asc\", partitionKeys=\"a_i\"), on=\"a_i asc\"), workers=\"2\", zkHost=\""+zkHost+"\", sort=\"a_i asc\")");

    List<Tuple> tuples = getTuples(pstream);



    assert(tuples.size() == 9);
    assertOrder(tuples, 0,1,2,3,4,7,6,8,9);

    //Test descending

    pstream = (ParallelStream)streamFactory.constructStream("parallel(collection1, merge(search(collection1, q=\"id:(4 1 8 9)\", fl=\"id,a_s,a_i\", sort=\"a_i desc\", partitionKeys=\"a_i\"), search(collection1, q=\"id:(0 2 3 6)\", fl=\"id,a_s,a_i\", sort=\"a_i desc\", partitionKeys=\"a_i\"), on=\"a_i desc\"), workers=\"2\", zkHost=\""+zkHost+"\", sort=\"a_i desc\")");

    tuples = getTuples(pstream);

    assert(tuples.size() == 8);
    assertOrder(tuples, 9,8,6,4,3,2,1,0);

    del("*:*");
    commit();
  }


  protected List<Tuple> getTuples(TupleStream tupleStream) throws IOException {
    tupleStream.open();
    List<Tuple> tuples = new ArrayList<Tuple>();
    for(Tuple t = tupleStream.read(); !t.EOF; t = tupleStream.read()) {
      tuples.add(t);
    }
    tupleStream.close();
    return tuples;
  }
  protected boolean assertOrder(List<Tuple> tuples, int... ids) throws Exception {
    return assertOrderOf(tuples, "id", ids);
  }
  protected boolean assertOrderOf(List<Tuple> tuples, String fieldName, int... ids) throws Exception {
    int i = 0;
    for(int val : ids) {
      Tuple t = tuples.get(i);
      Long tip = (Long)t.get(fieldName);
      if(tip.intValue() != val) {
        throw new Exception("Found value:"+tip.intValue()+" expecting:"+val);
      }
      ++i;
    }
    return true;
  }

  protected boolean assertGroupOrder(Tuple tuple, int... ids) throws Exception {
    List<?> group = (List<?>)tuple.get("tuples");
    int i=0;
    for(int val : ids) {
      Map<?,?> t = (Map<?,?>)group.get(i);
      Long tip = (Long)t.get("id");
      if(tip.intValue() != val) {
        throw new Exception("Found value:"+tip.intValue()+" expecting:"+val);
      }
      ++i;
    }
    return true;
  }

  public boolean assertLong(Tuple tuple, String fieldName, long l) throws Exception {
    long lv = (long)tuple.get(fieldName);
    if(lv != l) {
      throw new Exception("Longs not equal:"+l+" : "+lv);
    }

    return true;
  }
  
  public boolean assertString(Tuple tuple, String fieldName, String expected) throws Exception {
    String actual = (String)tuple.get(fieldName);
    
    if( (null == expected && null != actual) ||
        (null != expected && null == actual) ||
        (null != expected && !expected.equals(actual))){
      throw new Exception("Longs not equal:"+expected+" : "+actual);
    }

    return true;
  }
  
  protected boolean assertMaps(List<Map> maps, int... ids) throws Exception {
    if(maps.size() != ids.length) {
      throw new Exception("Expected id count != actual map count:"+ids.length+":"+maps.size());
    }

    int i=0;
    for(int val : ids) {
      Map t = maps.get(i);
      Long tip = (Long)t.get("id");
      if(tip.intValue() != val) {
        throw new Exception("Found value:"+tip.intValue()+" expecting:"+val);
      }
      ++i;
    }
    return true;
  }

  @Override
  protected void indexr(Object... fields) throws Exception {
    SolrInputDocument doc = getDoc(fields);
    indexDoc(doc);
  }
}
