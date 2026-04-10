package sr.leo.karoo_squadrats.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CollectedSquadratDao {

    @Query("SELECT * FROM collected_squadrats WHERE x BETWEEN :xMin AND :xMax AND y BETWEEN :yMin AND :yMax")
    suspend fun findInBounds(xMin: Int, xMax: Int, yMin: Int, yMax: Int): List<CollectedSquadrat>

    @Query("SELECT COUNT(*) FROM collected_squadrats")
    suspend fun count(): Int

    @Query("SELECT * FROM collected_squadrats")
    suspend fun getAll(): List<CollectedSquadrat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tiles: List<CollectedSquadrat>)

    @Query("DELETE FROM collected_squadrats WHERE x BETWEEN :xMin AND :xMax AND y BETWEEN :yMin AND :yMax")
    suspend fun deleteInBounds(xMin: Int, xMax: Int, yMin: Int, yMax: Int)

    @Query("DELETE FROM collected_squadrats")
    suspend fun deleteAll()

    @Query("SELECT MAX(syncedAt) FROM collected_squadrats")
    suspend fun maxSyncedAt(): Long?
}
