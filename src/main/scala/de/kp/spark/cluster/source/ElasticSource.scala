package de.kp.spark.cluster.source
/* Copyright (c) 2014 Dr. Krusche & Partner PartG
* 
* This file is part of the Spark-Cluster project
* (https://github.com/skrusche63/spark-outlier).
* 
* Spark-Cluster is free software: you can redistribute it and/or modify it under the
* terms of the GNU General Public License as published by the Free Software
* Foundation, either version 3 of the License, or (at your option) any later
* version.
* 
* Spark-Cluster is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
* A PARTICULAR PURPOSE. See the GNU General Public License for more details.
* You should have received a copy of the GNU General Public License along with
* Spark-Cluster. 
* 
* If not, see <http://www.gnu.org/licenses/>.
*/

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

import de.kp.spark.cluster.Configuration

import de.kp.spark.cluster.io.ElasticReader

import de.kp.spark.cluster.model._
import de.kp.spark.cluster.spec.{FeatureSpec,SequenceSpec}

import scala.collection.mutable.ArrayBuffer

class ElasticSource(@transient sc:SparkContext) extends Serializable {
          
  /* Retrieve data from Elasticsearch */    
  val conf = Configuration.elastic                          
 
  def features(params:Map[String,Any]):RDD[LabeledPoint] = {
    
    val index = params("source.index").asInstanceOf[String]
    val mapping = params("source.type").asInstanceOf[String]
    
    val query = params("query").asInstanceOf[String]
    
    val spec = sc.broadcast(FeatureSpec.get)
    
    /* Connect to Elasticsearch */
    val rawset = new ElasticReader(sc,index,mapping,query).read
    rawset.map(data => {
      
      val fields = spec.value

      val label = data(fields.head)
      val features = ArrayBuffer.empty[Double]
      
      for (field <- fields.tail) {
        features += data(field).toDouble
      }
      
      new LabeledPoint(label,features.toArray)
      
    })
    
  }
  
  def sequences(params:Map[String,Any] = Map.empty[String,Any]):RDD[NumberedSequence] = {
    /*
     * Elasticsearch is used as a data source as well as a data sink;
     * this implies that the respective indexes and mappings have to
     * be distinguished
     */    
    val index = params("source.index").asInstanceOf[String]
    val mapping = params("source.type").asInstanceOf[String]
    
    val query = params("query").asInstanceOf[String]
     
    val spec = sc.broadcast(SequenceSpec.get)

    /* Connect to Elasticsearch */
    val rawset = new ElasticReader(sc,index,mapping,query).read
    val dataset = rawset.map(data => {
      
      val site = data(spec.value("site")._1)
      val timestamp = data(spec.value("timestamp")._1).toLong

      val user = data(spec.value("user")._1)      
      val group = data(spec.value("group")._1)

      val item  = data(spec.value("item")._1)
      
      (site,user,group,timestamp,item)
      
    })
    /*
     * Group dataset by site & user and aggregate all items of a
     * certain group and all groups into a time-ordered sequence
     * representation that is compatible to the SPMF format.
     */
    val sequences = dataset.groupBy(v => (v._1,v._2)).map(data => {
      
      /*
       * Aggregate all items of a certain group onto a single
       * line thereby sorting these items in ascending order.
       * 
       * And then, sort these items by timestamp in ascending
       * order.
       */
      val groups = data._2.groupBy(_._3).map(group => {

        val timestamp = group._2.head._4
        val items = group._2.map(_._5.toInt).toList.distinct.sorted.mkString(" ")

        (timestamp,items)
        
      }).toList.sortBy(_._1)
      
      /*
       * Finally aggregate all sorted item groups (or sets) in a single
       * line and use SPMF format
       */
      groups.map(_._2).mkString(" -1 ") + " -2"
      
    }).coalesce(1)

    val ids = sc.parallelize(Range.Long(0,sequences.count,1),sequences.partitions.size)
    val zip = sequences.zip(ids).map(valu => (valu._2.toInt,valu._1))

    zip.map(valu => {
      
      val (sid,seq) = valu  
      val itemsets = seq.replace("-2", "").split(" -1 ").map(v => v.split(" ").map(_.toInt))
      
      new NumberedSequence(sid,itemsets)

    })
    
  }

}