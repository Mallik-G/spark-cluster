package de.kp.spark.cluster.actor
/* Copyright (c) 2014 Dr. Krusche & Partner PartG
 * 
 * This file is part of the Spark-Cluster project
 * (https://github.com/skrusche63/spark-cluster).
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

import akka.actor.{Actor,ActorLogging}

import de.kp.spark.cluster.model._

import de.kp.spark.cluster.io.ElasticWriter
import de.kp.spark.cluster.io.{ElasticBuilderFactory => EBF}

import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap

class ClusterTracker extends Actor with ActorLogging {
  
  def receive = {
    
    case req:ServiceRequest => {
     
      val origin = sender    
      val uid = req.data("uid")

      req.task match {

        case "track:features" => {
          
          val data = Map("uid" -> uid, "message" -> Messages.DATA_TO_TRACK_RECEIVED(uid))
          val response = new ServiceResponse(req.service,req.task,data,ClusterStatus.SUCCESS)	
      
          origin ! Serializer.serializeResponse(response)
          
          // TODO
          
        }
        
        case "track:sequences" => {
          
          val data = Map("uid" -> uid, "message" -> Messages.DATA_TO_TRACK_RECEIVED(uid))
          val response = new ServiceResponse(req.service,req.task,data,ClusterStatus.SUCCESS)	
      
          origin ! Serializer.serializeResponse(response)
          
          createSequence(req)
          context.stop(self)
          
        }
        
        case _ => {
          
          val msg = Messages.TASK_IS_UNKNOWN(uid,req.task)
          
          origin ! Serializer.serializeResponse(failure(req,msg))
          context.stop(self)
          
        }
        
      }

    }
    
  }
  
  private def prepareSequence(params:Map[String,String]):java.util.Map[String,Object] = {
    
    val source = HashMap.empty[String,String]
    
    source += EBF.SITE_FIELD -> params(EBF.SITE_FIELD)
    source += EBF.USER_FIELD -> params(EBF.USER_FIELD)
      
    source += EBF.TIMESTAMP_FIELD -> params(EBF.TIMESTAMP_FIELD)
 
    source += EBF.GROUP_FIELD -> params(EBF.GROUP_FIELD)
    source += EBF.ITEM_FIELD -> params(EBF.ITEM_FIELD)

    source
    
  }

  private def createSequence(req:ServiceRequest) {
          
    try {
      /*
       * Elasticsearch is used as a source and also as a sink; this implies
       * that the respective index and mapping must be distinguished; the source
       * index and mapping used here is the same as for ElasticSource
       */
      val index   = req.data("source.index")
      val mapping = req.data("source.type")
    
      val builder = EBF.getBuilder("item",mapping)
      val writer = new ElasticWriter()
    
      /* Prepare index and mapping for write */
      val readyToWrite = writer.open(index,mapping,builder)
      if (readyToWrite == false) {
      
        writer.close()
      
        val msg = String.format("""Opening index '%s' and maping '%s' for write failed.""",index,mapping)
        throw new Exception(msg)
      
      } else {
      
        /* Prepare data */
        val source = prepareSequence(req.data)
        /*
         * Writing this source to the respective index throws an
         * exception in case of an error; note, that the writer is
         * automatically closed 
         */
        writer.write(index, mapping, source)
        
      }
      
    } catch {
        
      case e:Exception => {
        log.error(e, e.getMessage())
      }
      
    } finally {

    }
    
  }

  private def failure(req:ServiceRequest,message:String):ServiceResponse = {
    
    if (req == null) {
      val data = Map("message" -> message)
      new ServiceResponse("","",data,ClusterStatus.FAILURE)	
      
    } else {
      val data = Map("uid" -> req.data("uid"), "message" -> message)
      new ServiceResponse(req.service,req.task,data,ClusterStatus.FAILURE)	
    
    }

  }
 
}