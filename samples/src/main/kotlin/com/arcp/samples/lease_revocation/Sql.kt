package com.arcp.samples.lease_revocation

/** sqlglot-backed read/write/ddl + table extractor. Stub. */

internal data class Classified(val op: String, val tables: List<String>)

internal fun classify(sql: String): Classified = TODO("sqlglot/jsqlparser-equivalent classifier")
