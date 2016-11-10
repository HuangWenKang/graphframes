/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.graphframes.lib

import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.storage.StorageLevel

import org.graphframes.{GraphFrame, Logging}

/**
 * Connected components algorithm.
 *
 * Computes the connected component membership of each vertex and returns a DataFrame of vertex
 * information with each vertex assigned a component ID.
 *
 * The resulting DataFrame contains all the vertex information and one additional column:
 *  - component (`LongType`): unique ID for this component
 */
class ConnectedComponents private[graphframes] (
    private val graph: GraphFrame) extends Arguments with Logging {

  import org.graphframes.lib.ConnectedComponents._

  private var broadcastThreshold: Int = 1000000

  /**
   * Sets broadcast threshold in propagating component assignments (default: 1000000).
   * If a node degree is greater than this threshold at some iteration, its component assignment
   * will be collected and then broadcasted back to propagate the assignment to its neighbors.
   * Otherwise, the assignment propagation is done by a normal Spark join.
   * This parameter is only used when the algorithm is set to "graphframes".
   */
  def setBroadcastThreshold(value: Int): this.type = {
    require(value >= 0, s"Broadcast threshold must be non-negative but got $value.")
    broadcastThreshold = value
    this
  }

  /**
   * Gets broadcast threshold in propagating component assignment.
   * @see [[setBroadcastThreshold()]]
   */
  def getBroadcastThreshold: Int = broadcastThreshold

  private var algorithm: String = _

  /**
   * Sets the connected components algorithm to use (default: "graphframes").
   * Supported algorithms are:
   *   - "graphframes": Uses alternating large star and small star iterations proposed in
   *     [[http://dx.doi.org/10.1145/2670979.2670997 Connected Components in MapReduce and Beyond]]
   *     with skewed join optimization.
   *   - "graphx": Converts the graph to a GraphX graph and then uses the connected components
   *     implementation in GraphX.
   * @see [[ConnectedComponents.supportedAlgorithms]]
   */
  def setAlgorithm(value: String): this.type = {
    require(supportedAlgorithms.contains(value),
      s"Supported algorithms are {${supportedAlgorithms.mkString(", ")}}, but got $value.")
    algorithm = value
    this
  }

  /**
   * Gets the connected component algorithm to use.
   * @see [[setAlgorithm()]].
   */
  def getAlgorithm: String = algorithm

  private var checkpointInterval: Int = 1

  /**
   * Sets checkpoint interval in terms of number of iterations (default: 1).
   * Checkpointing regularly helps recover from failures, clean shuffle files, and shorten the
   * lineage of the computation graph.
   * Checkpoint data is saved under [[org.apache.spark.SparkContext.getCheckpointDir]] with
   * prefix "connected-components".
   * If the checkpoint directory is not set, this throws an [[java.io.IOException]].
   * Set a nonpositive value to disable checkpointing (not recommended for large graphs).
   * This parameter is only used when the algorithm is set to "graphframes".
   * Its default value might change in the future.
   * @see [[org.apache.spark.SparkContext.setCheckpointDir()]]
   */
  def setCheckpointInterval(value: Int): this.type = {
    checkpointInterval = value
    this
  }

  /**
   * Gets checkpoint interval.
   * @see [[setCheckpointInterval()]]
   */
  def getCheckpointInterval: Int = checkpointInterval

  /**
   * Runs the algorithm.
   */
  def run(): DataFrame = {
    ConnectedComponents.run(graph,
      algorithm = algorithm,
      broadcastThreshold = broadcastThreshold,
      checkpointInterval = checkpointInterval)
  }
}

private object ConnectedComponents extends Logging {

  import org.graphframes.GraphFrame._

  private val COMPONENT = "component"
  private val ORIG_ID = "orig_id"
  private val MIN_NBR = "min_nbr"
  private val CNT = "cnt"
  private val CHECKPOINT_NAME_PREFIX = "connected-components"

  private val ALGO_GRAPHX = "graphx"
  private val ALGO_GRAPHFRAMES = "graphframes"

  /**
   * Supported algorithms in [[ConnectedComponents.setAlgorithm()]]: "graphframes" and "graphx".
   */
  val supportedAlgorithms: Array[String] = Array(ALGO_GRAPHX, ALGO_GRAPHFRAMES)

  /**
   * Returns the symmetric directed graph of the graph specified by input edges.
   * @param ee non-bidirectional edges
   */
  private def symmetrize(ee: DataFrame): DataFrame = {
    //// The following might work better but it doesn't compile in 1.6,
    //// because DST is nullable from aggregation but SRC is not.
    // val EDGE = "_edge"
    // ee.select(explode(
    //    array(struct(col(SRC), col(DST)), struct(col(DST).as(SRC), col(SRC).as(DST)))).as(EDGE))
    //  .select(col(s"$EDGE.$SRC").as(SRC), col(s"$EDGE.$DST").as(DST))

    ee.unionAll(ee.select(col(DST).as(SRC), col(SRC).as(DST)))
  }

  /**
   * Prepares the input graph for computing connected components by:
   *   - de-duplicating vertices and assigning unique long IDs to each,
   *   - changing edge directions to have increasing long IDs from src to dst,
   *   - de-duplicating edges and removing self-loops.
   * In the returned GraphFrame, the vertex DataFrame has two columns:
   *   - column `id` stores a long ID assigned to the vertex,
   *   - column `attr` stores the original vertex attributes.
   * The edge DataFrame has two columns:
   *   - column `src` stores the long ID of the source vertex,
   *   - column `dst` stores the long ID of the destination vertex,
   * where we always have `src` < `dst`.
   */
  private def prepare(graph: GraphFrame): GraphFrame = {
    // TODO: This assignment job might fail if the graph is skewed.
    val vertices = graph.indexedVertices
      .select(col(LONG_ID).as(ID), col(ATTR))
      // TODO: confirm the contract for a graph and decide whether we need distinct here
      // .distinct()
    val edges = graph.indexedEdges
      .select(col(LONG_SRC).as(SRC), col(LONG_DST).as(DST))
    val orderedEdges = edges.filter(col(SRC) !== col(DST))
      .select(minValue(col(SRC), col(DST)).as(SRC), maxValue(col(SRC), col(DST)).as(DST))
      .distinct()
    GraphFrame(vertices, orderedEdges)
  }

  /**
   * Returns the min neighbors of each vertex in a DataFrame with three columns:
   *   - `src`, the ID of the vertex
   *   - `min_nbr`, the min ID of its neighbors
   *   - `cnt`, the total number of neighbors
   * @return a DataFrame with three columns
   */
  private def minNbrs(ee: DataFrame): DataFrame = {
    symmetrize(ee).groupBy(SRC).agg(min(col(DST)).as(MIN_NBR), count("*").as(CNT))
  }

  private def minValue(x: Column, y: Column): Column = {
    when(x < y, x).otherwise(y)
  }

  private def maxValue(x: Column, y: Column): Column = {
    when(x > y, x).otherwise(y)
  }

  /**
   * Performs a possibly skewed join between edges and current component assignments.
   * The skew join is done by broadcast join for frequent keys and normal join for the rest.
   */
  private def skewedJoin(
      edges: DataFrame,
      minNbrs: DataFrame,
      broadcastThreshold: Int,
      logPrefix: String): DataFrame = {
    import edges.sqlContext.implicits._
    val hubs = minNbrs.filter(col(CNT) > broadcastThreshold)
      .select(SRC)
      .as[Long]
      .collect()
      .toSet
    GraphFrame.skewedJoin(edges, minNbrs, SRC, hubs, logPrefix)
  }

  /**
   * Runs connected components with default parameters.
   */
  def run(graph: GraphFrame): DataFrame = {
    new ConnectedComponents(graph).run()
  }

  private def runGraphX(graph: GraphFrame): DataFrame = {
    val components = org.apache.spark.graphx.lib.ConnectedComponents.run(graph.cachedTopologyGraphX)
    GraphXConversions.fromGraphX(graph, components, vertexNames = Seq(COMPONENT)).vertices
  }

  private def run(
      graph: GraphFrame,
      algorithm: String,
      broadcastThreshold: Int,
      checkpointInterval: Int): DataFrame = {
    if (algorithm == ALGO_GRAPHX) {
      return runGraphX(graph)
    }

    val runId = UUID.randomUUID().toString.takeRight(8)
    val logPrefix = s"[CC $runId]"
    logger.info(s"$logPrefix Start connected components with run ID $runId.")

    val sqlContext = graph.sqlContext
    val sc = sqlContext.sparkContext

    val shouldCheckpoint = checkpointInterval > 0
    val checkpointDir: Option[String] = if (shouldCheckpoint) {
      val dir = sc.getCheckpointDir.map { d =>
        new Path(d, s"$CHECKPOINT_NAME_PREFIX-$runId").toString
      }.getOrElse {
        throw new IOException(
          "Checkpoint directory is not set. Please set it first using sc.setCheckpointDir().")
      }
      logger.info(s"$logPrefix Using $dir for checkpointing with interval $checkpointInterval.")
      Some(dir)
    } else {
      logger.info(
        s"$logPrefix Checkpointing is disabled because checkpointInterval=$checkpointInterval.")
      None
    }

    logger.info(s"$logPrefix Preparing the graph for connected component computation ...")
    val g = prepare(graph)
    val vv = g.vertices
    var ee = g.edges.persist(StorageLevel.MEMORY_AND_DISK)
    val numEdges = ee.count()
    logger.info(s"$logPrefix Found $numEdges edges after preparation.")

    var converged = false
    var iteration = 1
    var prevSum: BigDecimal = null
    while (!converged) {
      // large-star step
      // compute min neighbors including self
      val minNbrs1 = minNbrs(ee)
        .withColumn(MIN_NBR, minValue(col(SRC), col(MIN_NBR)).as(MIN_NBR))
        .persist(StorageLevel.MEMORY_AND_DISK)
      // connect all strictly larger neighbors to the min neighbor (including self)
      ee = skewedJoin(ee, minNbrs1, broadcastThreshold, logPrefix)
        .select(col(DST).as(SRC), col(MIN_NBR).as(DST)) // src > dst
        .distinct()
        .persist(StorageLevel.MEMORY_AND_DISK)

      // small-star step
      // compute min neighbors
      val minNbrs2 = minNbrs(ee)
        .persist(StorageLevel.MEMORY_AND_DISK)
      // connect all smaller neighbors to the min neighbor
      ee = skewedJoin(ee, minNbrs2, broadcastThreshold, logPrefix)
        .select(col(MIN_NBR).as(SRC), col(DST)) // src <= dst
      // connect self to the min neighbor
      ee = ee
        .unionAll(
          minNbrs2.select( // src <= dst
            minValue(col(SRC), col(MIN_NBR)).as(SRC),
            maxValue(col(SRC), col(MIN_NBR)).as(DST)))
        .filter(col(SRC) !== col(DST)) // src < dst
        .distinct()

      // checkpointing
      if (shouldCheckpoint && (iteration % checkpointInterval == 0)) {
        // TODO: remove this after DataFrame.checkpoint is implemented
        val out = s"${checkpointDir.get}/$iteration"
        ee.write.parquet(out)
        // may hit S3 eventually consistent issue
        ee = sqlContext.read.parquet(out)

        // remove previous checkpoint
        if (iteration > checkpointInterval) {
          FileSystem.get(sc.hadoopConfiguration)
            .delete(new Path(s"${checkpointDir.get}/${iteration - checkpointInterval}"), true)
        }

        System.gc() // hint Spark to clean shuffle directories
      }

      ee.persist(StorageLevel.MEMORY_AND_DISK)

      // test convergence

      // Taking the sum in DecimalType to preserve precision.
      // We use 20 digits for long values and Spark SQL will add 10 digits for the sum.
      // It should be able to handle 200 billion edges without overflow.
      val (currSum, cnt) = ee.select(sum(col(SRC).cast(DecimalType(20, 0))), count("*")).rdd
        .map { r =>
          (r.getAs[BigDecimal](0), r.getLong(1))
        }.first()
      if (cnt != 0L && currSum == null) {
        throw new ArithmeticException(
          s"""
             |The total sum of edge src IDs is used to determine convergence during iterations.
             |However, the total sum at iteration $iteration exceeded 30 digits (1e30),
             |which should happen only if the graph contains more than 200 billion edges.
             |If not, please file a bug report at https://github.com/graphframes/graphframes/issues.
            """.stripMargin)
      }
      logInfo(s"$logPrefix Sum of assigned components in iteration $iteration: $currSum.")
      if (currSum == prevSum) {
        // This also covers the case when cnt = 0 and currSum is null, which means no edges.
        converged = true
      } else {
        prevSum = currSum
      }

      iteration += 1
    }

    logger.info(s"$logPrefix Connected components converged in ${iteration - 1} iterations.")

    logger.info(s"$logPrefix Join and return component assignments with original vertex IDs.")
    vv.join(ee, vv(ID) === ee(DST), "left_outer")
      .select(vv(ATTR), when(ee(SRC).isNull, vv(ID)).otherwise(ee(SRC)).as(COMPONENT))
      .select(col(s"$ATTR.*"), col(COMPONENT))
  }
}
