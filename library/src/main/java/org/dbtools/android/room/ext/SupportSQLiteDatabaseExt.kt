@file:Suppress("unused")

package org.dbtools.android.room.ext

import android.arch.persistence.db.SupportSQLiteDatabase
import org.dbtools.android.room.util.DatabaseUtil
import org.dbtools.android.room.util.MergeDatabaseUtil
import timber.log.Timber
import java.io.File

/**
 * Preform a PRAGMA check on the database and optionally check a table for existing data
 *
 * @param databaseNameTag Optional check on a table for data. (optional)
 * @param databaseNameTag Optional tag name to help identify database in logging
 *
 * @return true if validation check is OK
 */
fun SupportSQLiteDatabase.validDatabaseFile(databaseNameTag: String = "", tableDataCountCheck: String = ""): Boolean {
    return DatabaseUtil.validDatabaseFile(this, databaseNameTag, tableDataCountCheck)
}

/**
 * Attach a database
 * @param toDatabasePath Path to attach database
 * @param toDatabaseName Alias name for attached database
 */
fun SupportSQLiteDatabase.attachDatabase(toDatabasePath: String, toDatabaseName: String) {
    val sql = "ATTACH DATABASE '$toDatabasePath' AS $toDatabaseName"
    execSQL(sql)
}

/**
 * Detach a database
 * @param databaseName Alias name for attached database
 */
fun SupportSQLiteDatabase.detachDatabase(databaseName: String) {
    val sql = "DETACH DATABASE '$databaseName'"
    execSQL(sql)
}

/**
 * Find names of tables in this database
 * @param databaseName Alias name for database (such as an attached database) (optional)
 */
fun SupportSQLiteDatabase.findTableNames(databaseName: String = ""): List<String> {
    val tableNamesCursor = if (databaseName.isNotBlank()) {
        query("SELECT tbl_name FROM $databaseName.sqlite_master where type='table'")
    } else {
        query("SELECT tbl_name FROM sqlite_master where type='table'")
    }

    val tableNames = ArrayList<String>(tableNamesCursor.count)
    if (tableNamesCursor.moveToFirst()) {
        do {
            tableNames.add(tableNamesCursor.getString(0))
        } while (tableNamesCursor.moveToNext())
    }
    tableNamesCursor.close()

    return tableNames
}

/**
 * Determines if table exist
 *
 * @param tableName Table name to check for
 * @param databaseName Alias name for database (such as an attached database) (optional)
 *
 * @return true If tableName exist
 */
fun SupportSQLiteDatabase.tableExists(tableName: String, databaseName: String = ""): Boolean {
    return tablesExists(listOf(tableName), databaseName)
}

/**
 * Determines if tables exist
 * @param tableNames Table names to check for
 * @param databaseName Alias name for database (such as an attached database) (optional)
 *
 * @return true If ALL tableNames exist
 */
fun SupportSQLiteDatabase.tablesExists(tableNames: List<String>, databaseName: String = ""): Boolean {
    val inClaus = tableNames.joinToString(",", prefix = "(", postfix = ")") { "'$it'" }

    val tableNamesCursor = if (databaseName.isNotBlank()) {
        query("SELECT count(1) FROM $databaseName.sqlite_master WHERE type='table' AND tbl_name IN $inClaus")
    } else {
        query("SELECT count(1) FROM sqlite_master WHERE type='table' AND tbl_name IN $inClaus")
    }

    var tableCount = 0
    if (tableNamesCursor.moveToFirst()) {
        do {
            tableCount = tableNamesCursor.getInt(0)
        } while (tableNamesCursor.moveToNext())
    }
    tableNamesCursor.close()

    return tableCount == tableNames.size
}

/**
 * Merge database tables from other databases.
 *
 * By default all tables (except Room system tables) will be merged)
 *
 * @param fromDatabaseFile Sqlite file that will be opened and attached to this database... then data will be copied from this database File
 * @param includeTables Only table names in this list will be merged.  default: emptyList()
 * @param excludeTables All tables except the table names in this list will be merged.  default: emptyList()
 * @param mergeBlock Code to execute to perform merge.  default: database.execSQL("INSERT OR IGNORE INTO $tableName SELECT * FROM $sourceTableName")
 *
 * NOTE:  Room system tables are automatically excluded from the merge
 *
 *
 * Add the following function to the RoomDatabase class (mDatabase is ONLY accessable from inside the RoomDatabase class)
 *     fun mergeDataFromOtherDatabase(fromDatabaseFile: File, includeTables: List<String> = emptyList(), excludeTables: List<String> = emptyList()) {
 *         // make sure database is open
 *         if (!isOpen) {
 *             openHelper.writableDatabase
 *         }
 *
 *         // merge database
 *         mDatabase.mergeDatabase(fromDatabaseFile, includeTables, excludeTables)
 *     }
 */
fun SupportSQLiteDatabase.mergeDatabase(
    fromDatabaseFile: File,
    includeTables: List<String> = emptyList(),
    excludeTables: List<String> = emptyList(),
    mergeBlock: (database: SupportSQLiteDatabase, sourceTableName: String, targetTableName: String) -> Unit = { database, sourceTableName, targetTableName ->
        MergeDatabaseUtil.defaultMerge(database, sourceTableName, targetTableName)
    }
): Boolean {
    return MergeDatabaseUtil.mergeDatabase(this, fromDatabaseFile, includeTables, excludeTables, mergeBlock)
}

/**
 * Apply many SQL statements from a file. File must contain SQL statements separated by ;
 * All statements are executed in a single transaction
 *
 * @param sqlFile File containing statements
 *
 * @return true If all SQL statements successfully were applied
 */
fun SupportSQLiteDatabase.applySqlFile(sqlFile: File): Boolean {
    if (!sqlFile.exists()) {
        // Can't apply if there is no file
        Timber.e("Failed to apply sql file. File: [%s] does NOT exist", sqlFile.absolutePath)
        return false
    }

    try {
        beginTransaction()
        var statement = ""
        sqlFile.forEachLine { line ->
            statement += line
            if (statement.endsWith(';')) {
                this.execSQL(statement)
                statement = ""
            } else {
                // If the statement currently does not end with [;] then there must be multiple lines to the full statement.
                // Make sure to keep the newline character (some text columns may have multiple lines of data)
                statement += '\n'
            }
        }
        setTransactionSuccessful()
    } catch (e: Exception) {
        Timber.e(e, "Failed to apply sql file. File: [%s] Error: [%s]", sqlFile.absolutePath, e.message)
        return false
    } finally {
        endTransaction()
    }
    return true
}
