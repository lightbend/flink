/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.api

import _root_.java.lang.{Boolean => JBool}

import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.HepMatchOrder
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeField, RelDataTypeFieldImpl, RelRecordType}
import org.apache.calcite.sql2rel.RelDecorrelator
import org.apache.calcite.tools.{RuleSet, RuleSets}
import org.apache.flink.api.common.functions.MapFunction
import org.apache.flink.api.common.typeinfo.{SqlTimeTypeInfo, TypeInformation}
import org.apache.flink.api.common.typeutils.CompositeType
import org.apache.flink.api.java.tuple.{Tuple2 => JTuple2}
import org.apache.flink.api.java.typeutils.{RowTypeInfo, TupleTypeInfo}
import org.apache.flink.api.scala.typeutils.CaseClassTypeInfo
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.calcite.{FlinkTypeFactory, RelTimeIndicatorConverter}
import org.apache.flink.table.catalog.CatalogManager
import org.apache.flink.table.descriptors.{ConnectorDescriptor, StreamTableDescriptor}
import org.apache.flink.table.explain.PlanJsonParser
import org.apache.flink.table.expressions._
import org.apache.flink.table.operations.DataStreamTableOperation
import org.apache.flink.table.plan.nodes.FlinkConventions
import org.apache.flink.table.plan.nodes.datastream.{DataStreamRel, UpdateAsRetractionTrait}
import org.apache.flink.table.plan.rules.FlinkRuleSets
import org.apache.flink.table.plan.schema._
import org.apache.flink.table.plan.util.UpdatingPlanChecker
import org.apache.flink.table.runtime.conversion._
import org.apache.flink.table.runtime.types.{CRow, CRowTypeInfo}
import org.apache.flink.table.runtime.{CRowMapRunner, OutputRowtimeProcessFunction}
import org.apache.flink.table.sinks._
import org.apache.flink.table.sources.{StreamTableSource, TableSource, TableSourceUtil}
import org.apache.flink.table.typeutils.FieldInfoUtils.{calculateTableSchema, getFieldsInfo, isReferenceByPosition}
import org.apache.flink.table.typeutils.{TimeIndicatorTypeInfo, TypeCheckUtils}

import _root_.scala.collection.JavaConverters._

/**
  * The abstract base class for the implementation of stream TableEnvironments.
  *
  * @param execEnv The [[StreamExecutionEnvironment]] which is wrapped in this
  *                [[StreamTableEnvImpl]].
  * @param config  The [[TableConfig]] of this [[StreamTableEnvImpl]].
  */
abstract class StreamTableEnvImpl(
    private[flink] val execEnv: StreamExecutionEnvironment,
    config: TableConfig,
    catalogManager: CatalogManager)
  extends TableEnvImpl(config, catalogManager) {

  override def queryConfig: StreamQueryConfig = new StreamQueryConfig

  /**
    * Registers an internal [[StreamTableSource]] in this [[TableEnvImpl]]'s catalog without
    * name checking. Registered tables can be referenced in SQL queries.
    *
    * @param tableSource The [[TableSource]] to register.
    */
  override protected def validateTableSource(tableSource: TableSource[_]): Unit = {

    TableSourceUtil.validateTableSource(tableSource)
    tableSource match {

      // check for proper stream table source
      case streamTableSource: StreamTableSource[_] =>
        // check that event-time is enabled if table source includes rowtime attributes
        if (TableSourceUtil.hasRowtimeAttribute(streamTableSource) &&
          execEnv.getStreamTimeCharacteristic != TimeCharacteristic.EventTime) {
            throw new TableException(
              s"A rowtime attribute requires an EventTime time characteristic in stream " +
                s"environment. But is: ${execEnv.getStreamTimeCharacteristic}")
        }

      // not a stream table source
      case _ =>
        throw new TableException("Only StreamTableSource can be registered in " +
          "StreamTableEnvironment")
    }
  }

  override protected  def validateTableSink(configuredSink: TableSink[_]): Unit = {
    if (!configuredSink.isInstanceOf[StreamTableSink[_]]) {
      throw new TableException(
        "Only AppendStreamTableSink, UpsertStreamTableSink, and RetractStreamTableSink can be " +
          "registered in StreamTableEnvironment.")
    }
  }

  def connect(connectorDescriptor: ConnectorDescriptor): StreamTableDescriptor = {
    new StreamTableDescriptor(this, connectorDescriptor)
  }

  /**
    * Writes a [[Table]] to a [[TableSink]].
    *
    * Internally, the [[Table]] is translated into a [[DataStream]] and handed over to the
    * [[TableSink]] to write it.
    *
    * @param inputTable The [[Table]] to write.
    * @param sink The [[TableSink]] to write the [[Table]] to.
    * @param queryConfig The configuration for the query to generate.
    * @tparam T The expected type of the [[DataStream]] which represents the [[Table]].
    */
  override private[flink] def writeToSink[T](
      inputTable: Table,
      sink: TableSink[T],
      queryConfig: QueryConfig): Unit = {

    val table = inputTable.asInstanceOf[TableImpl]
    // Check query configuration
    val streamQueryConfig = queryConfig match {
      case streamConfig: StreamQueryConfig => streamConfig
      case _ =>
        throw new TableException("StreamQueryConfig required to configure stream query.")
    }

    sink match {

      case retractSink: RetractStreamTableSink[_] =>
        // retraction sink can always be used
        val outputType = sink.getOutputType
        // translate the Table into a DataStream and provide the type that the TableSink expects.
        val result: DataStream[T] =
          translate(
            table,
            streamQueryConfig,
            updatesAsRetraction = true,
            withChangeFlag = true)(outputType)
        // Give the DataStream to the TableSink to emit it.
        retractSink.asInstanceOf[RetractStreamTableSink[Any]]
          .emitDataStream(result.asInstanceOf[DataStream[JTuple2[JBool, Any]]])

      case upsertSink: UpsertStreamTableSink[_] =>
        // optimize plan
        val optimizedPlan = optimize(table.getRelNode, updatesAsRetraction = false)
        // check for append only table
        val isAppendOnlyTable = UpdatingPlanChecker.isAppendOnly(optimizedPlan)
        upsertSink.setIsAppendOnly(isAppendOnlyTable)
        // extract unique key fields
        val tableKeys: Option[Array[String]] = UpdatingPlanChecker.getUniqueKeyFields(optimizedPlan)
        // check that we have keys if the table has changes (is not append-only)
        tableKeys match {
          case Some(keys) => upsertSink.setKeyFields(keys)
          case None if isAppendOnlyTable => upsertSink.setKeyFields(null)
          case None if !isAppendOnlyTable => throw new TableException(
            "UpsertStreamTableSink requires that Table has full primary keys if it is updated.")
        }
        val outputType = sink.getOutputType
        val resultType = getResultType(table.getRelNode, optimizedPlan)
        // translate the Table into a DataStream and provide the type that the TableSink expects.
        val result: DataStream[T] =
          translate(
            optimizedPlan,
            resultType,
            streamQueryConfig,
            withChangeFlag = true)(outputType)
        // Give the DataStream to the TableSink to emit it.
        upsertSink.asInstanceOf[UpsertStreamTableSink[Any]]
          .emitDataStream(result.asInstanceOf[DataStream[JTuple2[JBool, Any]]])

      case appendSink: AppendStreamTableSink[_] =>
        // optimize plan
        val optimizedPlan = optimize(table.getRelNode, updatesAsRetraction = false)
        // verify table is an insert-only (append-only) table
        if (!UpdatingPlanChecker.isAppendOnly(optimizedPlan)) {
          throw new TableException(
            "AppendStreamTableSink requires that Table has only insert changes.")
        }
        val outputType = sink.getOutputType
        val resultType = getResultType(table.getRelNode, optimizedPlan)
        // translate the Table into a DataStream and provide the type that the TableSink expects.
        val result: DataStream[T] =
          translate(
            optimizedPlan,
            resultType,
            streamQueryConfig,
            withChangeFlag = false)(outputType)
        // Give the DataStream to the TableSink to emit it.
        appendSink.asInstanceOf[AppendStreamTableSink[T]].emitDataStream(result)

      case _ =>
        throw new TableException("Stream Tables can only be emitted by AppendStreamTableSink, " +
          "RetractStreamTableSink, or UpsertStreamTableSink.")
    }
  }

  /**
    * Creates a final converter that maps the internal row type to external type.
    *
    * @param inputTypeInfo the input of the sink
    * @param schema the input schema with correct field names (esp. for POJO field mapping)
    * @param requestedTypeInfo the output type of the sink
    * @param functionName name of the map function. Must not be unique but has to be a
    *                     valid Java class identifier.
    */
  protected def getConversionMapper[OUT](
      inputTypeInfo: TypeInformation[CRow],
      schema: RowSchema,
      requestedTypeInfo: TypeInformation[OUT],
      functionName: String)
    : MapFunction[CRow, OUT] = {

    val converterFunction = generateRowConverterFunction[OUT](
      inputTypeInfo.asInstanceOf[CRowTypeInfo].rowType,
      schema,
      requestedTypeInfo,
      functionName
    )

    converterFunction match {

      case Some(func) =>
        new CRowMapRunner[OUT](func.name, func.code, func.returnType)

      case _ =>
        new CRowToRowMapFunction().asInstanceOf[MapFunction[CRow, OUT]]
    }
  }

  /**
    * Creates a converter that maps the internal CRow type to Scala or Java Tuple2 with change flag.
    *
    * @param physicalTypeInfo the input of the sink
    * @param schema the input schema with correct field names (esp. for POJO field mapping)
    * @param requestedTypeInfo the output type of the sink.
    * @param functionName name of the map function. Must not be unique but has to be a
    *                     valid Java class identifier.
    */
  private def getConversionMapperWithChanges[OUT](
      physicalTypeInfo: TypeInformation[CRow],
      schema: RowSchema,
      requestedTypeInfo: TypeInformation[OUT],
      functionName: String)
    : MapFunction[CRow, OUT] = requestedTypeInfo match {

    // Scala tuple
    case t: CaseClassTypeInfo[_]
      if t.getTypeClass == classOf[(_, _)] && t.getTypeAt(0) == Types.BOOLEAN =>

      val reqType = t.getTypeAt[Any](1)

      // convert Row into requested type and wrap result in Tuple2
      val converterFunction = generateRowConverterFunction(
        physicalTypeInfo.asInstanceOf[CRowTypeInfo].rowType,
        schema,
        reqType,
        functionName
      )

      converterFunction match {

        case Some(func) =>
          new CRowToScalaTupleMapRunner(
            func.name,
            func.code,
            requestedTypeInfo.asInstanceOf[TypeInformation[(Boolean, Any)]]
          ).asInstanceOf[MapFunction[CRow, OUT]]

        case _ =>
          new CRowToScalaTupleMapFunction().asInstanceOf[MapFunction[CRow, OUT]]
      }

    // Java tuple
    case t: TupleTypeInfo[_]
      if t.getTypeClass == classOf[JTuple2[_, _]] && t.getTypeAt(0) == Types.BOOLEAN =>

      val reqType = t.getTypeAt[Any](1)

      // convert Row into requested type and wrap result in Tuple2
      val converterFunction = generateRowConverterFunction(
        physicalTypeInfo.asInstanceOf[CRowTypeInfo].rowType,
        schema,
        reqType,
        functionName
      )

      converterFunction match {

        case Some(func) =>
          new CRowToJavaTupleMapRunner(
            func.name,
            func.code,
            requestedTypeInfo.asInstanceOf[TypeInformation[JTuple2[JBool, Any]]]
          ).asInstanceOf[MapFunction[CRow, OUT]]

        case _ =>
          new CRowToJavaTupleMapFunction().asInstanceOf[MapFunction[CRow, OUT]]
      }
  }

  protected def asTableOperation[T](
      dataStream: DataStream[T],
      fields: Option[Array[Expression]])
    : DataStreamTableOperation[T] = {
    val streamType = dataStream.getType

    // get field names and types for all non-replaced fields
    val (indices, names) = fields match {
      case Some(f) =>
        // validate and extract time attributes
        val fieldsInfo = getFieldsInfo[T](streamType, f)
        val (rowtime, proctime) = validateAndExtractTimeAttributes(streamType, f)

        // check if event-time is enabled
        if (rowtime.isDefined &&
          execEnv.getStreamTimeCharacteristic != TimeCharacteristic.EventTime) {
          throw new TableException(
            s"A rowtime attribute requires an EventTime time characteristic in stream environment" +
              s". But is: ${execEnv.getStreamTimeCharacteristic}")
        }

        // adjust field indexes and field names
        val indexesWithIndicatorFields = adjustFieldIndexes(
          fieldsInfo.getIndices,
          rowtime,
          proctime)
        val namesWithIndicatorFields = adjustFieldNames(
          fieldsInfo.getFieldNames,
          rowtime,
          proctime)

        (indexesWithIndicatorFields, namesWithIndicatorFields)
      case None =>
        val fieldsInfo = getFieldsInfo[T](streamType)
        (fieldsInfo.getIndices, fieldsInfo.getFieldNames)
    }

    val dataStreamTable = new DataStreamTableOperation(
      dataStream,
      indices,
      calculateTableSchema(streamType, indices, names))
    dataStreamTable
  }

  /**
    * Checks for at most one rowtime and proctime attribute.
    * Returns the time attributes.
    *
    * @return rowtime attribute and proctime attribute
    */
  private def validateAndExtractTimeAttributes(
    streamType: TypeInformation[_],
    exprs: Array[Expression])
  : (Option[(Int, String)], Option[(Int, String)]) = {

    val (isRefByPos, fieldTypes) = streamType match {
      case c: CompositeType[_] =>
        // determine schema definition mode (by position or by name)
        (isReferenceByPosition(c, exprs), (0 until c.getArity).map(i => c.getTypeAt(i)).toArray)
      case t: TypeInformation[_] =>
        (false, Array(t))
    }

    var fieldNames: List[String] = Nil
    var rowtime: Option[(Int, String)] = None
    var proctime: Option[(Int, String)] = None

    def checkRowtimeType(t: TypeInformation[_]): Unit = {
      if (!(TypeCheckUtils.isLong(t) || TypeCheckUtils.isTimePoint(t))) {
        throw new TableException(
          s"The rowtime attribute can only replace a field with a valid time type, " +
          s"such as Timestamp or Long. But was: $t")
      }
    }

    def extractRowtime(idx: Int, name: String, origName: Option[String]): Unit = {
      if (rowtime.isDefined) {
        throw new TableException(
          "The rowtime attribute can only be defined once in a table schema.")
      } else {
        // if the fields are referenced by position,
        // it is possible to replace an existing field or append the time attribute at the end
        if (isRefByPos) {
          // aliases are not permitted
          if (origName.isDefined) {
            throw new TableException(
              s"Invalid alias '${origName.get}' because fields are referenced by position.")
          }
          // check type of field that is replaced
          if (idx < fieldTypes.length) {
            checkRowtimeType(fieldTypes(idx))
          }
        }
        // check reference-by-name
        else {
          val aliasOrName = origName.getOrElse(name)
          streamType match {
            // both alias and reference must have a valid type if they replace a field
            case ct: CompositeType[_] if ct.hasField(aliasOrName) =>
              val t = ct.getTypeAt(ct.getFieldIndex(aliasOrName))
              checkRowtimeType(t)
            // alias could not be found
            case _ if origName.isDefined =>
              throw new TableException(s"Alias '${origName.get}' must reference an existing field.")
            case _ => // ok
          }
        }

        rowtime = Some(idx, name)
      }
    }

    def extractProctime(idx: Int, name: String): Unit = {
      if (proctime.isDefined) {
          throw new TableException(
            "The proctime attribute can only be defined once in a table schema.")
      } else {
        // if the fields are referenced by position,
        // it is only possible to append the time attribute at the end
        if (isRefByPos) {

          // check that proctime is only appended
          if (idx < fieldTypes.length) {
            throw new TableException(
              "The proctime attribute can only be appended to the table schema and not replace " +
                s"an existing field. Please move '$name' to the end of the schema.")
          }
        }
        // check reference-by-name
        else {
          streamType match {
            // proctime attribute must not replace a field
            case ct: CompositeType[_] if ct.hasField(name) =>
              throw new TableException(
                s"The proctime attribute '$name' must not replace an existing field.")
            case _ => // ok
          }
        }
        proctime = Some(idx, name)
      }
    }

    val bridgedFields = exprs.map(expressionBridge.bridge).toArray[Expression]
    bridgedFields.zipWithIndex.foreach {
      case (RowtimeAttribute(UnresolvedFieldReference(name)), idx) =>
        extractRowtime(idx, name, None)

      case (Alias(RowtimeAttribute(UnresolvedFieldReference(origName)), name, _), idx) =>
        extractRowtime(idx, name, Some(origName))

      case (ProctimeAttribute(UnresolvedFieldReference(name)), idx) =>
        extractProctime(idx, name)

      case (Alias(ProctimeAttribute(UnresolvedFieldReference(_)), name, _), idx) =>
        extractProctime(idx, name)

      case (UnresolvedFieldReference(name), _) => fieldNames = name :: fieldNames

      case (Alias(UnresolvedFieldReference(_), name, _), _) => fieldNames = name :: fieldNames

      case (e, _) =>
        throw new TableException(s"Time attributes can only be defined on field references. " +
          s"Rowtime attributes can replace existing fields, proctime attributes can not. " +
          s"But was: $e")
    }

    if (rowtime.isDefined && fieldNames.contains(rowtime.get._2)) {
      throw new TableException(
        "The rowtime attribute may not have the same name as an another field.")
    }

    if (proctime.isDefined && fieldNames.contains(proctime.get._2)) {
      throw new TableException(
        "The proctime attribute may not have the same name as an another field.")
    }

    (rowtime, proctime)
  }

  /**
    * Injects markers for time indicator fields into the field indexes.
    *
    * @param fieldIndexes The field indexes into which the time indicators markers are injected.
    * @param rowtime An optional rowtime indicator
    * @param proctime An optional proctime indicator
    * @return An adjusted array of field indexes.
    */
  private def adjustFieldIndexes(
    fieldIndexes: Array[Int],
    rowtime: Option[(Int, String)],
    proctime: Option[(Int, String)]): Array[Int] = {

    // inject rowtime field
    val withRowtime = rowtime match {
      case Some(rt) =>
        fieldIndexes.patch(rt._1, Seq(TimeIndicatorTypeInfo.ROWTIME_STREAM_MARKER), 0)
      case _ =>
        fieldIndexes
    }

    // inject proctime field
    val withProctime = proctime match {
      case Some(pt) =>
        withRowtime.patch(pt._1, Seq(TimeIndicatorTypeInfo.PROCTIME_STREAM_MARKER), 0)
      case _ =>
        withRowtime
    }

    withProctime
  }

  /**
    * Injects names of time indicator fields into the list of field names.
    *
    * @param fieldNames The array of field names into which the time indicator field names are
    *                   injected.
    * @param rowtime An optional rowtime indicator
    * @param proctime An optional proctime indicator
    * @return An adjusted array of field names.
    */
  private def adjustFieldNames(
    fieldNames: Array[String],
    rowtime: Option[(Int, String)],
    proctime: Option[(Int, String)]): Array[String] = {

    // inject rowtime field
    val withRowtime = rowtime match {
      case Some(rt) => fieldNames.patch(rt._1, Seq(rowtime.get._2), 0)
      case _ => fieldNames
    }

    // inject proctime field
    val withProctime = proctime match {
      case Some(pt) => withRowtime.patch(pt._1, Seq(proctime.get._2), 0)
      case _ => withRowtime
    }

    withProctime
  }

  /**
    * Returns the decoration rule set for this environment
    * including a custom RuleSet configuration.
    */
  protected def getDecoRuleSet: RuleSet = {
    calciteConfig.decoRuleSet match {

      case None =>
        getBuiltInDecoRuleSet

      case Some(ruleSet) =>
        if (calciteConfig.replacesDecoRuleSet) {
          ruleSet
        } else {
          RuleSets.ofList((getBuiltInDecoRuleSet.asScala ++ ruleSet.asScala).asJava)
        }
    }
  }

  /**
    * Returns the built-in normalization rules that are defined by the environment.
    */
  protected def getBuiltInNormRuleSet: RuleSet = FlinkRuleSets.DATASTREAM_NORM_RULES

  /**
    * Returns the built-in optimization rules that are defined by the environment.
    */
  protected def getBuiltInPhysicalOptRuleSet: RuleSet = FlinkRuleSets.DATASTREAM_OPT_RULES

  /**
    * Returns the built-in decoration rules that are defined by the environment.
    */
  protected def getBuiltInDecoRuleSet: RuleSet = FlinkRuleSets.DATASTREAM_DECO_RULES

  /**
    * Generates the optimized [[RelNode]] tree from the original relational node tree.
    *
    * @param relNode The root node of the relational expression tree.
    * @param updatesAsRetraction True if the sink requests updates as retraction messages.
    * @return The optimized [[RelNode]] tree
    */
  private[flink] def optimize(relNode: RelNode, updatesAsRetraction: Boolean): RelNode = {
    val convSubQueryPlan = optimizeConvertSubQueries(relNode)
    val expandedPlan = optimizeExpandPlan(convSubQueryPlan)
    val decorPlan = RelDecorrelator.decorrelateQuery(expandedPlan, getRelBuilder)
    val planWithMaterializedTimeAttributes =
      RelTimeIndicatorConverter.convert(decorPlan, getRelBuilder.getRexBuilder)
    val normalizedPlan = optimizeNormalizeLogicalPlan(planWithMaterializedTimeAttributes)
    val logicalPlan = optimizeLogicalPlan(normalizedPlan)

    val physicalPlan = optimizePhysicalPlan(logicalPlan, FlinkConventions.DATASTREAM)
    optimizeDecoratePlan(physicalPlan, updatesAsRetraction)
  }

  private[flink] def optimizeDecoratePlan(
      relNode: RelNode,
      updatesAsRetraction: Boolean): RelNode = {
    val decoRuleSet = getDecoRuleSet
    if (decoRuleSet.iterator().hasNext) {
      val planToDecorate = if (updatesAsRetraction) {
        relNode.copy(
          relNode.getTraitSet.plus(new UpdateAsRetractionTrait(true)),
          relNode.getInputs)
      } else {
        relNode
      }
      runHepPlannerSequentially(
        HepMatchOrder.BOTTOM_UP,
        decoRuleSet,
        planToDecorate,
        planToDecorate.getTraitSet)
    } else {
      relNode
    }
  }

  /**
    * Translates a [[Table]] into a [[DataStream]].
    *
    * The transformation involves optimizing the relational expression tree as defined by
    * Table API calls and / or SQL queries and generating corresponding [[DataStream]] operators.
    *
    * @param table The root node of the relational expression tree.
    * @param queryConfig The configuration for the query to generate.
    * @param updatesAsRetraction Set to true to encode updates as retraction messages.
    * @param withChangeFlag Set to true to emit records with change flags.
    * @param tpe The [[TypeInformation]] of the resulting [[DataStream]].
    * @tparam A The type of the resulting [[DataStream]].
    * @return The [[DataStream]] that corresponds to the translated [[Table]].
    */
  protected def translate[A](
      table: Table,
      queryConfig: StreamQueryConfig,
      updatesAsRetraction: Boolean,
      withChangeFlag: Boolean)(implicit tpe: TypeInformation[A]): DataStream[A] = {
    val relNode = table.asInstanceOf[TableImpl].getRelNode
    val dataStreamPlan = optimize(relNode, updatesAsRetraction)

    val rowType = getResultType(relNode, dataStreamPlan)

    translate(dataStreamPlan, rowType, queryConfig, withChangeFlag)
  }

  /**
    * Translates a logical [[RelNode]] into a [[DataStream]].
    *
    * @param logicalPlan The root node of the relational expression tree.
    * @param logicalType The row type of the result. Since the logicalPlan can lose the
    *                    field naming during optimization we pass the row type separately.
    * @param queryConfig     The configuration for the query to generate.
    * @param withChangeFlag Set to true to emit records with change flags.
    * @param tpe         The [[TypeInformation]] of the resulting [[DataStream]].
    * @tparam A The type of the resulting [[DataStream]].
    * @return The [[DataStream]] that corresponds to the translated [[Table]].
    */
  protected def translate[A](
      logicalPlan: RelNode,
      logicalType: RelDataType,
      queryConfig: StreamQueryConfig,
      withChangeFlag: Boolean)
      (implicit tpe: TypeInformation[A]): DataStream[A] = {

    // if no change flags are requested, verify table is an insert-only (append-only) table.
    if (!withChangeFlag && !UpdatingPlanChecker.isAppendOnly(logicalPlan)) {
      throw new TableException(
        "Table is not an append-only table. " +
        "Use the toRetractStream() in order to handle add and retract messages.")
    }

    // get CRow plan
    val plan: DataStream[CRow] = translateToCRow(logicalPlan, queryConfig)

    val rowtimeFields = logicalType
      .getFieldList.asScala
      .filter(f => FlinkTypeFactory.isRowtimeIndicatorType(f.getType))

    // convert the input type for the conversion mapper
    // the input will be changed in the OutputRowtimeProcessFunction later
    val convType = if (rowtimeFields.size > 1) {
      throw new TableException(
        s"Found more than one rowtime field: [${rowtimeFields.map(_.getName).mkString(", ")}] in " +
          s"the table that should be converted to a DataStream.\n" +
          s"Please select the rowtime field that should be used as event-time timestamp for the " +
          s"DataStream by casting all other fields to TIMESTAMP.")
    } else if (rowtimeFields.size == 1) {
      val origRowType = plan.getType.asInstanceOf[CRowTypeInfo].rowType
      val convFieldTypes = origRowType.getFieldTypes.map { t =>
        if (FlinkTypeFactory.isRowtimeIndicatorType(t)) {
          SqlTimeTypeInfo.TIMESTAMP
        } else {
          t
        }
      }
      CRowTypeInfo(new RowTypeInfo(convFieldTypes, origRowType.getFieldNames))
    } else {
      plan.getType
    }

    // convert CRow to output type
    val conversion: MapFunction[CRow, A] = if (withChangeFlag) {
      getConversionMapperWithChanges(
        convType,
        new RowSchema(logicalType),
        tpe,
        "DataStreamSinkConversion")
    } else {
      getConversionMapper(
        convType,
        new RowSchema(logicalType),
        tpe,
        "DataStreamSinkConversion")
    }

    val rootParallelism = plan.getParallelism

    val withRowtime = if (rowtimeFields.isEmpty) {
      // no rowtime field to set
      plan.map(conversion)
    } else {
      // set the only rowtime field as event-time timestamp for DataStream
      // and convert it to SQL timestamp
      plan.process(new OutputRowtimeProcessFunction[A](conversion, rowtimeFields.head.getIndex))
    }

    withRowtime
      .returns(tpe)
      .name(s"to: ${tpe.getTypeClass.getSimpleName}")
      .setParallelism(rootParallelism)
  }

  /**
    * Translates a logical [[RelNode]] plan into a [[DataStream]] of type [[CRow]].
    *
    * @param logicalPlan The logical plan to translate.
    * @param queryConfig  The configuration for the query to generate.
    * @return The [[DataStream]] of type [[CRow]].
    */
  protected def translateToCRow(
    logicalPlan: RelNode,
    queryConfig: StreamQueryConfig): DataStream[CRow] = {

    logicalPlan match {
      case node: DataStreamRel =>
        node.translateToPlan(this, queryConfig)
      case _ =>
        throw new TableException("Cannot generate DataStream due to an invalid logical plan. " +
          "This is a bug and should not happen. Please file an issue.")
    }
  }

  /**
    * Returns the record type of the optimized plan with field names of the logical plan.
    */
  private def getResultType(originRelNode: RelNode, optimizedPlan: RelNode): RelRecordType = {
    // zip original field names with optimized field types
    val fieldTypes = originRelNode.getRowType.getFieldList.asScala
      .zip(optimizedPlan.getRowType.getFieldList.asScala)
      // get name of original plan and type of optimized plan
      .map(x => (x._1.getName, x._2.getType))
      // add field indexes
      .zipWithIndex
      // build new field types
      .map(x => new RelDataTypeFieldImpl(x._1._1, x._2, x._1._2))

    // build a record type from list of field types
    new RelRecordType(
      fieldTypes.toList.asInstanceOf[List[RelDataTypeField]].asJava)
  }

  def explain(table: Table): String = {
    val ast = table.asInstanceOf[TableImpl].getRelNode
    val optimizedPlan = optimize(ast, updatesAsRetraction = false)
    val dataStream = translateToCRow(optimizedPlan, queryConfig)

    val env = dataStream.getExecutionEnvironment
    val jsonSqlPlan = env.getExecutionPlan

    val sqlPlan = PlanJsonParser.getSqlExecutionPlan(jsonSqlPlan, false)

    s"== Abstract Syntax Tree ==" +
        System.lineSeparator +
        s"${RelOptUtil.toString(ast)}" +
        System.lineSeparator +
        s"== Optimized Logical Plan ==" +
        System.lineSeparator +
        s"${RelOptUtil.toString(optimizedPlan)}" +
        System.lineSeparator +
        s"== Physical Execution Plan ==" +
        System.lineSeparator +
        s"$sqlPlan"
  }

}

