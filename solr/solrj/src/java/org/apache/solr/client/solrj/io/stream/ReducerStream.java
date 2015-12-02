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

package org.apache.solr.client.solrj.io.stream;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.comp.FieldComparator;
import org.apache.solr.client.solrj.io.comp.ExpressibleComparator;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionNamedParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionValue;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;

/**
 *  Iterates over a TupleStream and buffers Tuples that are equal based on a comparator.
 *  This allows tuples to be grouped by common field(s).
 *
 *  The read() method emits one tuple per group. The fields of the emitted Tuple reflect the first tuple
 *  encountered in the group.
 *
 *  Use the Tuple.getMaps() method to return all the Tuples in the group. This method returns
 *  a list of maps (including the group head), which hold the data for each Tuple in the group.
 *
 *  Note: The ReducerStream requires that it's underlying stream be sorted and partitioned by the same
 *  fields as it's comparator.
 *
 **/

public class ReducerStream extends TupleStream implements ExpressibleStream {

  private static final long serialVersionUID = 1;

  private PushBackStream tupleStream;
  private Comparator<Tuple> comp;

  private transient Tuple currentGroupHead;

  public ReducerStream(TupleStream tupleStream,
                       Comparator<Tuple> comp) {
    this.tupleStream = new PushBackStream(tupleStream);
    this.comp = comp;
  }
  
  public ReducerStream(StreamExpression expression, StreamFactory factory) throws IOException{
    // grab all parameters out
    List<StreamExpression> streamExpressions = factory.getExpressionOperandsRepresentingTypes(expression, ExpressibleStream.class, TupleStream.class);
    StreamExpressionNamedParameter byExpression = factory.getNamedOperand(expression, "by");
    
    // validate expression contains only what we want.
    if(expression.getParameters().size() != streamExpressions.size() + 1){
      throw new IOException(String.format(Locale.ROOT,"Invalid expression %s - unknown operands found", expression));
    }
    
    if(1 != streamExpressions.size()){
      throw new IOException(String.format(Locale.ROOT,"Invalid expression %s - expecting a single stream but found %d",expression, streamExpressions.size()));
    }
    this.tupleStream = new PushBackStream(factory.constructStream(streamExpressions.get(0)));
    
    if(null == byExpression || !(byExpression.getParameter() instanceof StreamExpressionValue)){
      throw new IOException(String.format(Locale.ROOT,"Invalid expression %s - expecting single 'by' parameter listing fields to group by but didn't find one",expression));
    }
    
    // Reducing is always done over equality, so always use an EqualTo comparator
    this.comp = factory.constructComparator(((StreamExpressionValue)byExpression.getParameter()).getValue(), FieldComparator.class);
  }

  @Override
  public StreamExpression toExpression(StreamFactory factory) throws IOException {    
    // function name
    StreamExpression expression = new StreamExpression(factory.getFunctionName(this.getClass()));
    
    // stream
    expression.addParameter(tupleStream.toExpression(factory));
    
    // over
    if(comp instanceof ExpressibleComparator){
      expression.addParameter(new StreamExpressionNamedParameter("by",((ExpressibleComparator)comp).toExpression(factory)));
    }
    else{
      throw new IOException("This ReducerStream contains a non-expressible comparator - it cannot be converted to an expression");
    }
    
    return expression;   
  }
  
  public void setStreamContext(StreamContext context) {
    this.tupleStream.setStreamContext(context);
  }

  public List<TupleStream> children() {
    List<TupleStream> l =  new ArrayList();
    l.add(tupleStream);
    return l;
  }

  public void open() throws IOException {
    tupleStream.open();
  }

  public void close() throws IOException {
    tupleStream.close();
  }

  public Tuple read() throws IOException {

    List<Map> maps = new ArrayList();
    while(true) {
      Tuple t = tupleStream.read();

      if(t.EOF) {
       if(maps.size() > 0) {
         tupleStream.pushBack(t);
         Map map1 = maps.get(0);
         Map map2 = new HashMap();
         map2.putAll(map1);
         Tuple groupHead = new Tuple(map2);
         groupHead.setMaps(maps);
         return groupHead;
       } else {
         return t;
       }
      }

      if(currentGroupHead == null) {
        currentGroupHead = t;
        maps.add(t.getMap());
      } else {
        if(comp.compare(currentGroupHead, t) == 0) {
          maps.add(t.getMap());
        } else {
          Tuple groupHead = currentGroupHead.clone();
          tupleStream.pushBack(t);
          currentGroupHead = null;
          groupHead.setMaps(maps);
          return groupHead;
        }
      }
    }
  }

  public int getCost() {
    return 0;
  }
}