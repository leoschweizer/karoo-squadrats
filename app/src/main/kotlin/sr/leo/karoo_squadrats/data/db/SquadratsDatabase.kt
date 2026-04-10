package sr.leo.karoo_squadrats.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CollectedSquadrat::class], version = 2)
abstract class SquadratsDatabase : RoomDatabase() {
    abstract fun collectedSquadratDao(): CollectedSquadratDao

    companion object {
        @Volatile
        private var instance: SquadratsDatabase? = null

        fun getInstance(context: Context): SquadratsDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SquadratsDatabase::class.java,
                    "squadrats.db",
                ).build().also { instance = it }
            }
        }
    }
}
