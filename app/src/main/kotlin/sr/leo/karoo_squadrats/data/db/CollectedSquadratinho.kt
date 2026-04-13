package sr.leo.karoo_squadrats.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "collected_squadratinhos",
    primaryKeys = ["x", "y"],
    indices = [Index(value = ["syncedAt"])],
)
data class CollectedSquadratinho(
    val x: Int,
    val y: Int,
    val syncedAt: Long,
)
