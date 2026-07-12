package com.tetraploid.joyforold.assist.server.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

object DatabaseFactory {
    fun init(databaseUrl: String) {
        if (databaseUrl.startsWith("jdbc:h2:file:")) {
            val path = databaseUrl.removePrefix("jdbc:h2:file:")
                .substringBefore(';')
            File(path).parentFile?.mkdirs()
        }
        Database.connect(
            url = databaseUrl,
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(Devices, PairSessions, FamilyBindings)
        }
    }
}
