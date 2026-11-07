package io.github.thiesi.chitchat.core.mentions

import kotlinx.serialization.json.*

internal fun mentionBody(rows: List<Pair<Long,String>> = listOf(8L to "Private suggestion")): String = buildJsonObject {
    put("users",buildJsonArray {
        rows.forEach { (id,name) -> add(buildJsonObject {
            put("id",id); put("username",name)
            put("ignored",true); put("can_send",true) // Unknown enrichment grants no policy/permission.
        }) }
    })
}.toString()
