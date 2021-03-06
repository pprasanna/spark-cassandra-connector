package com.datastax.spark.connector.mapper

import com.datastax.spark.connector.ColumnName
import com.datastax.spark.connector.cql.{ColumnDef, RegularColumn, PartitionKeyColumn, TableDef}
import com.datastax.spark.connector.types.ColumnType
import com.datastax.spark.connector.util.ReflectionUtil

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.{typeOf, Type, TypeTag}
import scala.util.{Success, Try}

/** A [[ColumnMapper]] that assumes camel case naming convention for property accessors and constructor names
  * and underscore naming convention for column names.
  *
  * Example mapping:
  * {{{
  *   case class User(
  *     login: String,         // mapped to "login" column
  *     emailAddress: String   // mapped to "email_address" column
  *     emailAddress2: String  // mapped to "email_address_2" column
  *   )
  * }}}
  *
  * Additionally, it is possible to name columns exactly the same as property names (case-sensitive):
  * {{{
  *   case class TaxPayer(
  *     TIN: String            // mapped to "TIN" column
  *   )
  * }}}
  *
  * @param columnNameOverride maps property names to column names; use it to override default mapping for some properties
  */
class DefaultColumnMapper[T : ClassTag : TypeTag](columnNameOverride: Map[String, String] = Map.empty) extends ColumnMapper[T] {

  import com.datastax.spark.connector.mapper.DefaultColumnMapper._

  override def classTag: ClassTag[T] = implicitly[ClassTag[T]]

  private def setterNameToPropertyName(str: String) =
    str.substring(0, str.length - SetterSuffix.length)

  private val constructorParams = ReflectionUtil.constructorParams[T]
  private val getters = ReflectionUtil.getters[T]
  private val setters = ReflectionUtil.setters[T]

  def resolve(name: String, tableDef: TableDef, aliasToColumnName: Map[String, String]): String =
    columnNameOverride orElse aliasToColumnName applyOrElse(name, ColumnMapperConvention.columnNameForProperty(_: String, tableDef))

  def constructorParamToColumnName(paramName: String, tableDef: TableDef, aliasToColumnName: Map[String, String]): String =
    resolve(paramName, tableDef, aliasToColumnName)

  def getterToColumnName(getterName: String, tableDef: TableDef, aliasToColumnName: Map[String, String]): String =
    resolve(getterName, tableDef, aliasToColumnName)

  def setterToColumnName(setterName: String, tableDef: TableDef, aliasToColumnName: Map[String, String]): String = {
    val propertyName = setterNameToPropertyName(setterName)
    resolve(propertyName, tableDef, aliasToColumnName)
  }

  override def columnMap(tableDef: TableDef, aliasToColumnName: Map[String, String]): ColumnMap = {
    val constructor =
      for ((paramName, _) <- constructorParams)
      yield ColumnName(constructorParamToColumnName(paramName, tableDef, aliasToColumnName))

    val getterMap =
      for ((getterName, _) <- getters)
      yield (getterName, ColumnName(getterToColumnName(getterName, tableDef, aliasToColumnName)))

    val setterMap =
      for ((setterName, _) <- setters)
      yield (setterName, ColumnName(setterToColumnName(setterName, tableDef, aliasToColumnName)))

    SimpleColumnMap(constructor, getterMap.toMap, setterMap.toMap, allowsNull = false)
  }

  private def inheritedScalaGetters: Seq[(String, Type)] = {
    for {
      bc <- typeOf[T].baseClasses if bc.fullName.startsWith("scala.")
      tpe = bc.typeSignature
      getter <- ReflectionUtil.getters(tpe)
    } yield getter
  }

  override def newTable(keyspaceName: String, tableName: String): TableDef = {
    // filter out inherited scala getters, because they are very likely
    // not the properties users want to map
    val inheritedScalaGetterNames = inheritedScalaGetters.map(_._1)
    val paramNames = constructorParams.map(_._1)
    val getterNames = getters.map(_._1).filterNot(inheritedScalaGetterNames.toSet.contains)
    val setterNames = setters.map(_._1).map(setterNameToPropertyName)
    val propertyNames = (paramNames ++ getterNames ++ setterNames)
      .distinct
      .filterNot(_.contains("$"))  // ignore any properties generated by Scala compiler

    // pick only those properties which we know Cassandra data type for:
    val getterTypes = getters.toMap
    val mappableProperties = propertyNames
        .map { name => (name, getterTypes(name)) }
        .map { case (name, tpe) => (name, Try(ColumnType.fromScalaType(tpe))) }
        .collect { case (name, Success(columnType)) => (name, columnType) }

    require(mappableProperties.size > 0, "No mappable properties found in class: " + classTag.runtimeClass.getName)

    val columns =
      for ((property, i) <- mappableProperties.zipWithIndex) yield {
        val propertyName = property._1
        val columnType = property._2
        val columnName = ColumnMapperConvention.camelCaseToUnderscore(propertyName)
        val columnRole = if (i == 0) PartitionKeyColumn else RegularColumn
        ColumnDef(columnName, columnRole, columnType)
      }
    TableDef(keyspaceName, tableName, Seq(columns.head), Seq.empty, columns.tail)
  }

}

object DefaultColumnMapper {
  private val SetterSuffix: String = "_$eq"
}
