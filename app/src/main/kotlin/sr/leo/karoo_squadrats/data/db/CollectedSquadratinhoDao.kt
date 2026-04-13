package sr.leo.karoo_squadrats.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CollectedSquadratinhoDao {

    @Query("SELECT * FROM collected_squadratinhos WHERE x BETWEEN :xMin AND :xMax AND y BETWEEN :yMin AND :yMax")
    suspend fun findInBounds(xMin: Int, xMax: Int, yMin: Int, yMax: Int): List<CollectedSquadratinho>

    @Query("SELECT COUNT(*) FROM collected_squadratinhos")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tiles: List<CollectedSquadratinho>)

    @Query("DELETE FROM collected_squadratinhos WHERE x BETWEEN :xMin AND :xMax AND y BETWEEN :yMin AND :yMax")
    suspend fun deleteInBounds(xMin: Int, xMax: Int, yMin: Int, yMax: Int)

    @Query("SELECT MAX(syncedAt) FROM collected_squadratinhos")
    suspend fun maxSyncedAt(): Long?
}
