package com.apollographql.apollo3.cache.normalized.api

import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.annotations.ApolloInternal
import com.apollographql.apollo3.cache.normalized.api.internal.RecordWeigher.calculateBytes
import com.apollographql.apollo3.mpp.currentTimeMillis
import com.benasher44.uuid.Uuid
import kotlin.jvm.JvmOverloads

/**
 * A normalized entry that corresponds to a response object. Object fields are stored if they are a GraphQL Scalars. If
 * a field is a GraphQL Object a [CacheKey] will be stored instead.
 */
class Record(
    val key: String,
    /**
     * a list of fields. Values can be
     * - Int
     * - Long
     * - Double
     * - Boolean
     * - String
     * - List
     * - CacheKey (for composite types)
     * - Map (for custom scalars)
     * - null
     */
    val fields: Map<String, Any?>,
    val mutationId: Uuid? = null,
) : Map<String, Any?> by fields {

  @ApolloExperimental
  var lastUpdated: Map<String, Long?>? = null
    private set

  @ApolloInternal
  constructor(
      key: String,
      fields: Map<String, Any?>,
      mutationId: Uuid?,
      lastUpdated: Map<String, Long?>,
  ) : this(key, fields, mutationId) {
    this.lastUpdated = lastUpdated
  }

  val sizeInBytes: Int
    get() {
      val datesSize = lastUpdated?.size?.times(8) ?: 0
      return calculateBytes(this) + datesSize
    }

  /**
   * Returns a merge result record and a set of field keys which have changed, or were added.
   * A field key incorporates any GraphQL arguments in addition to the field name.
   */
  @ApolloExperimental
  fun mergeWith(newRecord: Record, date: Long?): Pair<Record, Set<String>> {
    val changedKeys = mutableSetOf<String>()
    val mergedFields = fields.toMutableMap()
    val lastUpdated = lastUpdated?.toMutableMap() ?: mutableMapOf()

    for ((fieldKey, newFieldValue) in newRecord.fields) {
      val hasOldFieldValue = fields.containsKey(fieldKey)
      val oldFieldValue = fields[fieldKey]
      if (!hasOldFieldValue || oldFieldValue != newFieldValue) {
        mergedFields[fieldKey] = newFieldValue
        changedKeys.add("$key.$fieldKey")
      }
      // Even if the value did not change update date
      if (date != null) {
        lastUpdated[fieldKey] = date
      }
    }

    return Record(
        key = key,
        fields = mergedFields,
        mutationId = newRecord.mutationId,
        lastUpdated = lastUpdated
    ) to changedKeys
  }

  fun mergeWith(newRecord: Record): Pair<Record, Set<String>> {
    return mergeWith(newRecord, null)
  }


  /**
   * Returns a set of all field keys.
   * A field key incorporates any GraphQL arguments in addition to the field name.
   */
  fun fieldKeys(): Set<String> {
    return fields.keys.map { "$key.$it" }.toSet()
  }

  /**
   * Returns the list of referenced cache fields
   */
  fun referencedFields(): List<CacheKey> {
    val result = mutableListOf<CacheKey>()
    val stack = fields.values.toMutableList()
    while (stack.isNotEmpty()) {
      when (val value = stack.removeAt(stack.size - 1)) {
        is CacheKey -> result.add(value)
        is Map<*, *> -> stack.addAll(value.values)
        is List<*> -> stack.addAll(value)
      }
    }
    return result
  }

  companion object {
    internal fun changedKeys(record1: Record, record2: Record): Set<String> {
      check(record1.key == record2.key) {
        "Cannot compute changed keys on record with different keys: '${record1.key}' - '${record2.key}'"
      }
      val keys1 = record1.fields.keys
      val keys2 = record2.fields.keys
      val intersection = keys1.intersect(keys2)

      val changed = (keys1 - intersection) + (keys2 - intersection) + intersection.filter {
        record1.fields[it] != record2.fields[it]
      }

      return changed.map { "${record1.key}.$it" }.toSet()
    }
  }
}
