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

package org.apache.spark.sql.execution

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.{RDD, ShuffledRDD}
import org.apache.spark.shuffle.sort.SortShuffleManager
import org.apache.spark.sql.catalyst.CatalystTypeConverters
import org.apache.spark.sql.catalyst.errors._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.util.collection.ExternalSorter
import org.apache.spark.util.{CompletionIterator, MutablePair}
import org.apache.spark.{HashPartitioner, SparkEnv}

/**
 * :: DeveloperApi ::
 */
@DeveloperApi
case class Project(projectList: Seq[NamedExpression], child: SparkPlan) extends UnaryNode {
  override def output: Seq[Attribute] = projectList.map(_.toAttribute)

  @transient lazy val buildProjection = newMutableProjection(projectList, child.output)

  protected override def doExecute(): RDD[InternalRow] = child.execute().mapPartitions { iter =>
    val reusableProjection = buildProjection()
    iter.map(reusableProjection)
  }

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering
}

/**
 * :: DeveloperApi ::
 */
@DeveloperApi
case class Filter(condition: Expression, child: SparkPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output

  @transient lazy val conditionEvaluator: (InternalRow) => Boolean =
    newPredicate(condition, child.output)

  protected override def doExecute(): RDD[InternalRow] = child.execute().mapPartitions { iter =>
    iter.filter(conditionEvaluator)
  }

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering
}

/**
 * :: DeveloperApi ::
 * Sample the dataset.
 * @param lowerBound Lower-bound of the sampling probability (usually 0.0)
 * @param upperBound Upper-bound of the sampling probability. The expected fraction sampled
 *                   will be ub - lb.
 * @param withReplacement Whether to sample with replacement.
 * @param seed the random seed
 * @param child the QueryPlan
 */
@DeveloperApi
case class Sample(
    lowerBound: Double,
    upperBound: Double,
    withReplacement: Boolean,
    seed: Long,
    child: SparkPlan)
  extends UnaryNode
{
  override def output: Seq[Attribute] = child.output

  // TODO: How to pick seed?
  protected override def doExecute(): RDD[InternalRow] = {
    if (withReplacement) {
      child.execute().map(_.copy()).sample(withReplacement, upperBound - lowerBound, seed)
    } else {
      child.execute().map(_.copy()).randomSampleWithRange(lowerBound, upperBound, seed)
    }
  }
}

/**
 * :: DeveloperApi ::
 */
@DeveloperApi
case class Union(children: Seq[SparkPlan]) extends SparkPlan {
  // TODO: attributes output by union should be distinct for nullability purposes
  override def output: Seq[Attribute] = children.head.output
  protected override def doExecute(): RDD[InternalRow] =
    sparkContext.union(children.map(_.execute()))
}

/**
 * :: DeveloperApi ::
 * Take the first limit elements. Note that the implementation is different depending on whether
 * this is a terminal operator or not. If it is terminal and is invoked using executeCollect,
 * this operator uses something similar to Spark's take method on the Spark driver. If it is not
 * terminal or is invoked using execute, we first take the limit on each partition, and then
 * repartition all the data to a single partition to compute the global limit.
 */
@DeveloperApi
case class Limit(limit: Int, child: SparkPlan)
  extends UnaryNode {
  // TODO: Implement a partition local limit, and use a strategy to generate the proper limit plan:
  // partition local limit -> exchange into one partition -> partition local limit again

  /** We must copy rows when sort based shuffle is on */
  private def sortBasedShuffleOn = SparkEnv.get.shuffleManager.isInstanceOf[SortShuffleManager]

  override def output: Seq[Attribute] = child.output
  override def outputPartitioning: Partitioning = SinglePartition

  override def executeCollect(): Array[Row] = child.executeTake(limit)

  protected override def doExecute(): RDD[InternalRow] = {
    val rdd: RDD[_ <: Product2[Boolean, InternalRow]] = if (sortBasedShuffleOn) {
      child.execute().mapPartitions { iter =>
        iter.take(limit).map(row => (false, row.copy()))
      }
    } else {
      child.execute().mapPartitions { iter =>
        val mutablePair = new MutablePair[Boolean, InternalRow]()
        iter.take(limit).map(row => mutablePair.update(false, row))
      }
    }
    val part = new HashPartitioner(1)
    val shuffled = new ShuffledRDD[Boolean, InternalRow, InternalRow](rdd, part)
    shuffled.setSerializer(new SparkSqlSerializer(child.sqlContext.sparkContext.getConf))
    shuffled.mapPartitions(_.take(limit).map(_._2))
  }
}

/**
 * :: DeveloperApi ::
 * Take the first limit elements as defined by the sortOrder, and do projection if needed.
 * This is logically equivalent to having a [[Limit]] operator after a [[Sort]] operator,
 * or having a [[Project]] operator between them.
 * This could have been named TopK, but Spark's top operator does the opposite in ordering
 * so we name it TakeOrdered to avoid confusion.
 */
@DeveloperApi
case class TakeOrderedAndProject(
    limit: Int,
    sortOrder: Seq[SortOrder],
    projectList: Option[Seq[NamedExpression]],
    child: SparkPlan) extends UnaryNode {

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = SinglePartition

  private val ord: RowOrdering = new RowOrdering(sortOrder, child.output)

  // TODO: remove @transient after figure out how to clean closure at InsertIntoHiveTable.
  @transient private val projection = projectList.map(new InterpretedProjection(_, child.output))

  private def collectData(): Array[InternalRow] = {
    val data = child.execute().map(_.copy()).takeOrdered(limit)(ord)
    projection.map(data.map(_)).getOrElse(data)
  }

  override def executeCollect(): Array[Row] = {
    val converter = CatalystTypeConverters.createToScalaConverter(schema)
    collectData().map(converter(_).asInstanceOf[Row])
  }

  // TODO: Terminal split should be implemented differently from non-terminal split.
  // TODO: Pick num splits based on |limit|.
  protected override def doExecute(): RDD[InternalRow] = sparkContext.makeRDD(collectData(), 1)

  override def outputOrdering: Seq[SortOrder] = sortOrder
}

/**
 * :: DeveloperApi ::
 * Performs a sort on-heap.
 * @param global when true performs a global sort of all partitions by shuffling the data first
 *               if necessary.
 */
@DeveloperApi
case class Sort(
    sortOrder: Seq[SortOrder],
    global: Boolean,
    child: SparkPlan)
  extends UnaryNode {
  override def requiredChildDistribution: Seq[Distribution] =
    if (global) OrderedDistribution(sortOrder) :: Nil else UnspecifiedDistribution :: Nil

  protected override def doExecute(): RDD[InternalRow] = attachTree(this, "sort") {
    child.execute().mapPartitions( { iterator =>
      val ordering = newOrdering(sortOrder, child.output)
      iterator.map(_.copy()).toArray.sorted(ordering).iterator
    }, preservesPartitioning = true)
  }

  override def output: Seq[Attribute] = child.output

  override def outputOrdering: Seq[SortOrder] = sortOrder
}

/**
 * :: DeveloperApi ::
 * Performs a sort, spilling to disk as needed.
 * @param global when true performs a global sort of all partitions by shuffling the data first
 *               if necessary.
 */
@DeveloperApi
case class ExternalSort(
    sortOrder: Seq[SortOrder],
    global: Boolean,
    child: SparkPlan)
  extends UnaryNode {

  override def requiredChildDistribution: Seq[Distribution] =
    if (global) OrderedDistribution(sortOrder) :: Nil else UnspecifiedDistribution :: Nil

  protected override def doExecute(): RDD[InternalRow] = attachTree(this, "sort") {
    child.execute().mapPartitions( { iterator =>
      val ordering = newOrdering(sortOrder, child.output)
      val sorter = new ExternalSorter[InternalRow, Null, InternalRow](ordering = Some(ordering))
      sorter.insertAll(iterator.map(r => (r.copy, null)))
      val baseIterator = sorter.iterator.map(_._1)
      // TODO(marmbrus): The complex type signature below thwarts inference for no reason.
      CompletionIterator[InternalRow, Iterator[InternalRow]](baseIterator, sorter.stop())
    }, preservesPartitioning = true)
  }

  override def output: Seq[Attribute] = child.output

  override def outputOrdering: Seq[SortOrder] = sortOrder
}

/**
 * :: DeveloperApi ::
 * Return a new RDD that has exactly `numPartitions` partitions.
 */
@DeveloperApi
case class Repartition(numPartitions: Int, shuffle: Boolean, child: SparkPlan)
  extends UnaryNode {
  override def output: Seq[Attribute] = child.output

  protected override def doExecute(): RDD[InternalRow] = {
    child.execute().map(_.copy()).coalesce(numPartitions, shuffle)
  }
}


/**
 * :: DeveloperApi ::
 * Returns a table with the elements from left that are not in right using
 * the built-in spark subtract function.
 */
@DeveloperApi
case class Except(left: SparkPlan, right: SparkPlan) extends BinaryNode {
  override def output: Seq[Attribute] = left.output

  protected override def doExecute(): RDD[InternalRow] = {
    left.execute().map(_.copy()).subtract(right.execute().map(_.copy()))
  }
}

/**
 * :: DeveloperApi ::
 * Returns the rows in left that also appear in right using the built in spark
 * intersection function.
 */
@DeveloperApi
case class Intersect(left: SparkPlan, right: SparkPlan) extends BinaryNode {
  override def output: Seq[Attribute] = children.head.output

  protected override def doExecute(): RDD[InternalRow] = {
    left.execute().map(_.copy()).intersection(right.execute().map(_.copy()))
  }
}

/**
 * :: DeveloperApi ::
 * A plan node that does nothing but lie about the output of its child.  Used to spice a
 * (hopefully structurally equivalent) tree from a different optimization sequence into an already
 * resolved tree.
 */
@DeveloperApi
case class OutputFaker(output: Seq[Attribute], child: SparkPlan) extends SparkPlan {
  def children: Seq[SparkPlan] = child :: Nil

  protected override def doExecute(): RDD[InternalRow] = child.execute()
}
