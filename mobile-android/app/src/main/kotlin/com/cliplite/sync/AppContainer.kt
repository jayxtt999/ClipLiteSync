package com.cliplite.sync

import android.content.Context
import androidx.room.Room
import com.cliplite.sync.data.AppDatabase
import com.cliplite.sync.data.Prefs
import com.cliplite.sync.engine.SyncEngine

/** 手写单例容器（项目规模不需要 Hilt） */
class AppContainer(context: Context) {
    val prefs = Prefs(context)
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "clipsync.db")
        .fallbackToDestructiveMigration()
        .build()
    val engine = SyncEngine(context.applicationContext, prefs, db)
}
