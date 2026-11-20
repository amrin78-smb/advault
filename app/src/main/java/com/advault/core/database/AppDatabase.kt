package com.advault.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        BlocklistEntity::class,
        CustomRuleEntity::class,
        AppPolicyEntity::class,
        ActivityLogEntity::class,
        DailyStatsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blocklistDao(): BlocklistDao
    abstract fun customRuleDao(): CustomRuleDao
    abstract fun appPolicyDao(): AppPolicyDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun providerObservationDao(): ProviderObservationDao
    abstract fun dailyStatsDao(): DailyStatsDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "advault.db")
            .fallbackToDestructiveMigration(dropAllTables = true) // pre-1.0: no migrations yet
            .build()

    @Provides fun blocklistDao(db: AppDatabase) = db.blocklistDao()
    @Provides fun customRuleDao(db: AppDatabase) = db.customRuleDao()
    @Provides fun appPolicyDao(db: AppDatabase) = db.appPolicyDao()
    @Provides fun activityLogDao(db: AppDatabase) = db.activityLogDao()
    @Provides fun providerObservationDao(db: AppDatabase) = db.providerObservationDao()
    @Provides fun dailyStatsDao(db: AppDatabase) = db.dailyStatsDao()
}
